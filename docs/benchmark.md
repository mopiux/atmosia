# Harness de benchmark

Implementa la Sección 16 del documento de diseño: una forma repetible de medir el costo del
renderer de nubes, empezando por las **nubes vanilla**, que son la línea base contra la cual se
ratifica el criterio de aceptación (Sección 16.1).

> **Este código nunca se compiló ni se ejecutó.** Se escribió en un entorno sin acceso a Forge ni
> a Mojang, así que no hay una sola línea verificada contra el juego real. Esperá ajustes en la
> primera compilación. Los puntos donde la API de 1.20.1 hay que confirmarla están marcados con
> `VERIFICAR` en el código.

## Antes de compilar

`gradle.properties` tiene `forge_version=REEMPLAZAR`. Hay que poner la versión exacta de Forge
para 1.20.1 (Verificación 5 de la Fase 0). No se puso un número de memoria a propósito: uno
equivocado falla de formas confusas.

## Uso

Con un mundo cargado y trampas habilitadas:

```
/atmosiabench list
/atmosiabench run below_clouds
/atmosiabench run fast_travel 5 60      # calentamiento 5 s, medición 60 s
/atmosiabench abort
```

La corrida pasa el jugador a espectador, fija la hora del mundo, despeja el clima, apaga VSync y
el límite de FPS, recorre la trayectoria del escenario, y al terminar **restaura VSync y el
límite de FPS**. El modo de juego y las reglas del mundo no se restauran: son cambios que el
jugador ve y puede revertir, y devolverlos automáticamente podría pisar algo que quería dejar así.

## Escenarios

Los nueve de la Sección 16 del documento de diseño: `clear_sky`, `full_coverage`, `camera_still`,
`camera_spin`, `fast_travel`, `altitude_sweep`, `below_clouds`, `inside_clouds`, `above_clouds`.

Cada uno combina una altura relativa a la capa de nubes con un movimiento de cámara determinista.
La trayectoria es función del **tiempo transcurrido**, no del número de frame: una máquina a 30
FPS y otra a 300 recorren exactamente el mismo camino, que es lo que hace comparables las
mediciones entre fases.

`clear_sky` y `full_coverage` solo son plenamente aplicables cuando el renderer permite forzar la
cobertura. Con nubes vanilla no hay tal control, así que la corrida queda marcada en el CSV.

## Salida

En `<directorio del juego>/atmosia-benchmarks/`:

- **`results.csv`** — una fila por corrida, acumulativo. Es el archivo que se compara entre fases.
- **`frames-<escenario>-<timestamp>.csv`** — una fila por frame de esa corrida, para recalcular
  percentiles y ver dónde estuvieron los tirones en vez de confiar en un promedio.

Convención importante: **una celda vacía significa "no medido", nunca cero.** El tiempo de GPU
queda vacío si el driver no soporta consultas de temporización, y las columnas de regiones,
vértices y memoria quedan vacías mientras no exista un renderer propio registrado.

## Cómo se conecta Atmosia cuando exista

El harness mide tiempos sin saber qué está dibujando. Todo lo que depende de la implementación
entra por `CloudMetricsProvider`:

```java
CloudMetricsProvider.Registry.set(miProveedor);
```

Sin proveedor registrado, el harness mide igual y reporta la línea base de vanilla. Esa es la
razón de que el harness se escriba antes que el renderer y no después.

## Lo que este harness no mide

- **Draw calls y vértices de las nubes vanilla.** Minecraft no los expone por subsistema, y
  instrumentar GL para obtenerlos sería más invasivo que el valor que aportan. Para Atmosia sí se
  miden, vía el proveedor. Si hicieran falta los de vanilla, salen de una captura con RenderDoc.
- **Overdraw directamente.** La aproximación prevista por la Sección 9.5 es correr el mismo
  escenario a distintas resoluciones de ventana y comparar: si el costo escala con los píxeles, el
  cuello de botella es el relleno.
- **Memoria de GPU** mientras no haya proveedor: no hay una forma portable de pedírsela a OpenGL.

## Qué está verificado y qué no

La parte que no depende de Minecraft —estadísticas de frame, formato del CSV y trayectorias de
cámara— **sí está compilada y probada**, con un test que corre sin Gradle ni dependencias:

```
javac --release 17 -d /tmp/atmosia-test \
    src/main/java/dev/mopiux/atmosia/bench/CameraPath.java \
    src/main/java/dev/mopiux/atmosia/bench/BenchmarkScenario.java \
    src/main/java/dev/mopiux/atmosia/bench/FrameStats.java \
    src/main/java/dev/mopiux/atmosia/bench/CsvReporter.java \
    src/test/java/dev/mopiux/atmosia/bench/BenchSmokeTest.java
java -cp /tmp/atmosia-test dev.mopiux.atmosia.bench.BenchSmokeTest
```

Encontró un error real antes de que esto tocara el juego: el **1% low** estaba calculado como
`1000 / percentil99`, y con 101 frames de los cuales uno tarda 100 ms, el percentil 99 por rango
más cercano devuelve 10 ms y reporta 100 FPS — es decir, se comía justo el tirón que la métrica
existe para mostrar. Ahora es el promedio del 1% de frames más lentos, que es lo que reportan las
herramientas de benchmarking cuando dicen "1% low", y devuelve los 10 FPS correctos.

Lo que **no** está verificado: todo lo que toca la API de Minecraft o de OpenGL —el runner, los
enganches de eventos, los comandos y el temporizador de GPU—. Nunca se compiló contra Forge.

## Metodología

1. Correr cada escenario **con nubes vanilla** primero, y guardar ese `results.csv`.
2. Ratificar recién entonces el criterio de aceptación de la Sección 16.1, con la línea base a la
   vista.
3. Repetir los nueve escenarios al cierre de cada fase relevante (2, 3, 4 y 6).

Una corrida sola no dice nada: conviene repetir cada escenario tres veces y quedarse con la
mediana. Y siempre en la misma máquina, misma resolución, mismo render distance — las tres cosas
quedan registradas en el CSV justamente para poder verificarlo después.
