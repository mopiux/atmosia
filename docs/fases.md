# Cobertura de fases y estado de verificación

Qué cubre el código respecto de las fases de la Sección 15 del documento de diseño, y —lo más
importante— qué está verificado y qué no.

## Estado de verificación, primero

| Parte | Estado |
|---|---|
| `core/` — ruido, densidad, LOD, fade vertical, prioridad, presupuesto, regiones | **Compilado y probado.** 66 comprobaciones en `CoreSmokeTest`. |
| `bench/` — estadísticas, CSV, trayectorias de cámara | **Compilado y probado.** 26 comprobaciones en `BenchSmokeTest`. |
| `bench/` — runner, enganches, comandos, temporizador de GPU | Sin compilar. Toca Minecraft y OpenGL. |
| `client/` — renderer, caché, cola, supresión de vanilla, tipo de render | Sin compilar. Toca Minecraft y OpenGL. |

Los dos tests corren sin Gradle ni dependencias; el comando exacto está en la cabecera de cada
uno. Esa separación no es casual: el núcleo procedural se escribió deliberadamente sin tocar
Minecraft para que fuera verificable en un entorno que no puede ejecutar el juego.

## Fase por fase

### Fase 0 — Investigación y diseño
Aprobada, con la enmienda sobre las Verificaciones 1 y 4. Entregable en
`fase-0-analisis-y-arquitectura.md`.

### Fase 1 — Prototipo mínimo
- `VanillaCloudSuppressor`: sustituye los efectos de dimensión del Overworld por una versión sin
  nubes, **sin mixin**. Si eso falla, apaga el ajuste de nubes del juego como red de seguridad.
  Ninguna de las dos vías comparte punto de inyección con otros mods, que era el riesgo principal
  declarado del proyecto.
- `AtmosiaClientEvents`: dibujo después de los bloques translúcidos.
- `CloudRenderer`: una capa ya renderiza, con movimiento.

### Fase 2 — Cloud grid + cache
- `RegionKey`: regiones en **espacio de nube**, con el viento ya descontado. Consecuencia: la
  densidad de una región no cambia nunca y el movimiento es una traslación al dibujar, no una
  regeneración. Resuelve de una vez el requisito de movimiento y el de no regenerar.
- `CloudCache` (dentro de `CloudRenderer`): mapa región → malla, con desalojo por antigüedad de
  uso y tope configurable.
- `GenerationQueue`: densidad en hilos de trabajo, construcción de malla y subida a GPU en el hilo
  de render, que es donde vive el contexto de OpenGL.
- `CloudBudget`: tope de regiones y de cuádruples por frame. Lo que no entra se difiere, no se
  descarta: el cálculo ya está pagado.

### Fase 3 — LOD + culling
- `LodSelector`: distancia derivada del render distance del jugador, nunca un valor fijo.
- `LodLevel`: el LOD es menos slices y celdas más grandes, no la misma forma con menos vértices.
- `VerticalFade`: transición gradual, con descarte real solo cuando la opacidad ya está cerca de
  cero. El fade tapa el corte, que es lo que pide la corrección de la Sección 6.3.
- Frustum culling y prioridad de cola con las clases de la Sección 8.1.
- Al cambiar de LOD se sigue dibujando la malla vieja mientras se construye la nueva, para no
  abrir un agujero en el cielo al cruzar el umbral.

### Fase 4 — Multi-layer
- `CloudLayerDef`: tres capas con altura, color, velocidad y cobertura propias.
- LOD por capa: a distancia se dejan de dibujar las capas altas, que son las que menos se notan.
- Las capas comparten el campo de ruido con escalas distintas, así que donde coinciden la densidad
  se acumula y se lee como una masa más profunda en vez de como tres láminas.

### Fase 5 — Calidad visual
- Perfil vertical por slice: umbral alto en los extremos y bajo en el medio, que es lo que da la
  silueta redondeada en vez de láminas apiladas.
- Sombreado propio: la base de la capa más oscura que el techo, y las zonas densas algo más
  oscuras.
- Dispersión hacia adelante: borde luminoso cuando se mira hacia el sol.
- Tinte del momento del día tomado del color de nubes del propio juego, aplicado como uniforme:
  el sombreado horneado no hay que reconstruirlo cuando cambia la luz.
- Velocidades distintas por capa.

### Fase 6 — Optimización final
**No hecha, y no se puede hacer acá.** Es una fase definida por medición: encontrar allocations,
draw calls evitables y overdraw exige correr el juego y comparar contra las fases anteriores.
Declararla hecha sin datos contradiría la regla más repetida del documento de diseño.

## Hallazgos de la primera prueba real (2026-09-18)

Primera corrida en una máquina de verdad: Windows, render distance 16, 1920x991. Compiló y se
ejecutó. Tres problemas, y los tres ya corregidos en el código, sin volver a probar todavía.

**1. Las nubes vanilla seguían dibujándose.** Era el fallo más grave y el más fácil de ver: en las
capturas conviven las cajas 3D de vanilla con los planos de Atmosia. El CSV lo confirma desde otro
ángulo: `cloud_status` quedó en `fancy`, así que la supresión creyó haber funcionado y nunca cayó a
su red de seguridad.

La explicación más probable es que `ClientLevel` resuelve su `DimensionSpecialEffects` una sola vez
al construirse. Sustituir la entrada del mapa después de que el mundo ya existe no cambia nada, y
Atmosia se entera del mundo justo después de que carga. La hipótesis central de la Verificación 1
de la Fase 0 queda entonces refutada en su forma práctica: técnicamente el mecanismo existe, pero
no es aprovechable desde donde el mod puede actuar.

Ahora se apaga el ajuste de nubes del juego mientras Atmosia dibuja, guardando el valor previo y
restaurándolo al desactivarse. Sigue sin haber mixin y sigue sin compartir punto de inyección con
nadie, que era lo que importaba. Si el jugador ya tenía las nubes en OFF, Atmosia no se activa: ese
ajuste significa "no quiero nubes" y reemplazar las vanilla no da derecho a ignorarlo.

**2. El cielo se veía como sábanas rectangulares.** Dos causas que se sumaban. El domo de nubes
llegaba a 384 bloques con render distance 16, así que su borde caía dentro del campo de visión; y
las celdas de los niveles de detalle lejanos medían 64 y 128 bloques, que a cualquier distancia se
leen como un rectángulo y no como una nube. Las líneas rectas que cruzan el cielo en las capturas
son los bordes de esas celdas.

Corregido subiendo el piso del domo a 512 bloques y el multiplicador por defecto a 3, y acotando la
celda máxima a 64 bloques. El ahorro a distancia sale de los slices, que es donde está el costo de
relleno, no de agrandar celdas.

**3. El temporizador de GPU se colgaba.** Lo delataron los datos: en `fast_travel`, el mismo valor
de GPU repetido durante 1263 frames seguidos, el 72% de la corrida. Si el anillo de consultas se
llenaba no se abría una nueva, y como la cosecha solo ocurría con una consulta abierta, el anillo no
se vaciaba nunca más. Es un error que ninguna revisión de código encontró y que una sola corrida
real dejó en evidencia.

Todas las cifras de GPU de esa primera tanda quedan invalidadas. Las de CPU siguen valiendo.

### Lo que esas mediciones sí dicen

| Escenario | FPS medio | 1% low | CPU medio |
|---|---|---|---|
| fast_travel | 58,7 | 14,7 | 17,0 ms |
| above_clouds | 47,2 | 21,8 | 21,2 ms |
| camera_spin | 29,3 | 8,8 | 34,1 ms |

Con dos sistemas de nubes dibujándose a la vez, así que no son atribuibles solo a Atmosia. La
distancia entre el promedio y el 1% low —de 4 a 1 en el peor caso— dice que hay tirones, no una
caída pareja. Y `camera_spin` siendo el peor escenario apunta al culling y al orden de dibujo, que
es justo lo que ese escenario existe para estresar.

Falta la línea base de vanilla, que no se pudo medir porque vanilla estaba dibujándose encima. Con
la supresión arreglada, ahora se puede: poner el modo de nubes en **Vanilla** desde el menú del mod
(o `cloudMode = VANILLA` en la configuración) y correr los mismos escenarios.

## Hallazgos de la segunda prueba real (2026-09-18)

**Las nubes vanilla seguían apareciendo.** La corrección anterior apagaba el ajuste de nubes del
juego una sola vez, al activarse el mod, y daba el trabajo por hecho. Aplicar una vez y confiar deja
demasiadas formas de perder el ajuste: el menú de opciones lo reescribe al cerrarse, una recarga del
archivo de opciones lo devuelve a su valor guardado, y cualquier otro mod que lo toque gana por ser
el último. Ninguna de esas se puede prevenir desde afuera de LevelRenderer, pero todas se corrigen:
la supresión ahora se reaplica una vez por tick y cuenta cuántas veces tuvo que hacerlo.

Ese contador es la parte que faltaba. La primera prueba terminó en una pregunta que nadie podía
responder —si las nubes que se veían eran vanilla o de Atmosia— porque la única evidencia estaba en
el log. Ahora el estado está a la vista en el menú del mod, y el modo **Ninguna** deja el cielo
completamente vacío: si con ese modo queda alguna nube, es vanilla y la supresión falló. Eso es una
respuesta verificable, no una impresión.

También se corrigió que `coverageScale` estaba declarado en la configuración y no lo leía nadie: el
control de cantidad de nubes existía en el archivo y no hacía absolutamente nada.

La versión del mod pasa a 0.2.0. Hasta ahora todas las builds decían 0.0.1, así que la lista de mods
no permitía distinguir si lo que se estaba probando tenía las correcciones o no.

### Menú de configuración

El mod ya aparecía en la lista con el botón "Configuración" inerte. Ahora abre una pantalla con tres
controles:

| Control | Qué hace |
|---|---|
| **Nubes** | `Atmosia` / `Vanilla` / `Ninguna`. Es el interruptor principal y el diagnóstico. |
| **Calidad** | `Bajo` / `Medio` / `Alto` / `Personalizado`. |
| **Cantidad de nubes** | 20% a 200% de la cobertura de diseño de cada capa. |

Más un panel de estado que muestra el ajuste de nubes del juego, si Atmosia está dibujando, cuántas
regiones tiene en memoria y cuántas veces hubo que reaplicar la supresión.

Los tres perfiles se mueven en los dos ejes que de verdad cuestan —cortes por capa y alcance del
domo— y en ninguno más. En particular **ningún perfil agranda la celda**: una celda grande se lee
como un rectángulo en el cielo por lejos que esté, y eso es un defecto visual, no un ajuste de
calidad. Es la misma lección de la primera prueba, ahora convertida en invariante con test.

| | Bajo | Medio | Alto |
|---|---|---|---|
| Multiplicador de distancia | 1,5 | 3,0 | 4,5 |
| Cortes por capa (cerca) | 4 | 8 | 8 |
| Cuádruples por frame | 8.000 | 24.000 | 64.000 |
| Regiones en caché | 128 | 384 | 768 |

Cambiar el perfil no vacía la caché: el renderer ya detecta región por región que el LOD cambió y la
reemplaza sin abrir huecos. Cambiar la cantidad sí la vacía, porque la densidad misma cambia y eso
no se puede deducir de la clave de la región. Los resultados de densidad que estaban en vuelo cuando
se cambió la cantidad se descartan al llegar, para que no entre al cielo un pedazo del cielo
anterior.

El CSV del benchmark gana tres columnas —`cloud_mode`, `quality_profile`, `coverage_scale`— porque
sin ellas dos corridas del mismo escenario dejan de ser comparables y no hay forma de notarlo
después.

## Tercera prueba real (2026-09-18, 16:40)

**La supresión de vanilla funciona.** `cloud_status=off` en las cuatro corridas. Es la primera tanda
que mide Atmosia sola, sin las nubes del juego dibujándose encima, y la primera comparable consigo
misma.

**El temporizador de GPU quedó arreglado.** La racha máxima de valores idénticos bajó de 1263 frames
(72% de la corrida) a 1 o 2 frames. Las cifras de GPU vuelven a valer.

Perfil Medio, cantidad 120%, render distance 16, 1920×991:

| Escenario | FPS medio | 1% low | CPU mediana | GPU mediana | GPU p95 |
|---|---|---|---|---|---|
| `fast_travel` | 69,2 | 11,9 | 11,96 ms | 2,69 ms | 10,21 ms |
| `altitude_sweep` | 66,5 | 16,1 | 13,57 ms | 1,77 ms | 9,36 ms |
| `full_coverage` | 34,0 | 18,2 | 28,61 ms | 16,11 ms | 23,68 ms |
| `camera_spin` | 30,2 | 13,6 | 32,27 ms | 12,59 ms | 22,53 ms |

Tres hallazgos, en orden de importancia:

1. **Hay dos regímenes de costo distintos.** `fast_travel` y `altitude_sweep` tienen una distribución
   de GPU bimodal: mediana de 1,8–2,7 ms, pero entre el 18% y el 24% de los frames cuestan 7–9 ms.
   `full_coverage` y `camera_spin` no tienen un solo frame por encima del doble de su mediana. En
   movimiento el costo son los picos de subida de geometría; con el cielo lleno es relleno sostenido.
   Son dos problemas y se arreglan distinto.
2. **`camera_spin` está limitado por CPU, no por GPU**, y por 2,5 a 1 (32,27 ms contra 12,59). Eso
   contradice la expectativa de la Fase 0, que apuntaba al fill rate como cuello probable. El dato
   tiene una salvedad grande: el `cpu_ms` medido es el del frame completo, y girar la cámara también
   obliga a Minecraft a rehacer su culling de chunks.
3. **Los 1% low siguen siendo el problema real.** En `fast_travel`, media de 69 FPS y 1% low de 11,9:
   casi 6 a 1. El frame más lento fue de 166 ms. El sospechoso está identificado —`region_gen_ms`
   llegó a 1,26 ms y la construcción de malla corre en el hilo de render— pero no confirmado.

Sigue faltando la línea base de vanilla, que es lo que permitiría separar el costo de Atmosia del de
Minecraft en el punto 2. Se mide poniendo el modo de nubes en `Vanilla` y repitiendo los cuatro
escenarios.

### Un error que encontraron estos mismos datos

El `results.csv` de esta tanda quedó **corrido tres columnas**. Las columnas nuevas (`cloud_mode`,
`quality_profile`, `coverage_scale`) se agregaron debajo del encabezado viejo, porque el archivo ya
existía y el encabezado solo se escribía al crearlo. El archivo se abre sin error y todo lo que se
lea de él es mentira.

Un archivo de mediciones que miente en silencio es peor que no tenerlo. Corregido: si el encabezado
del archivo existente no coincide con el actual, el viejo se aparta como `results-anterior-<fecha>.csv`
y se empieza uno nuevo. No se pierde nada y ninguno de los dos queda inconsistente.

### Documentación

`docs/sistema-de-nubes.md` explica el sistema completo de generación —del ruido al dibujo— con sus
ventajas y sus costos, y con estas mediciones interpretadas.

## Artefactos visuales corregidos (0.2.2)

De las capturas del 18-19/09 volando con elytra por debajo de la capa. El detalle completo, con el
diagnóstico y los números, está en `docs/artefactos-visuales.md`.

**Las bandas grises no venían de acumulación de sombreado.** El corte más bajo tenía un color propio
de 0,480/0,486/0,502 — más oscuro que el cielo diurno sobre el que se mezcla. Donde se superponían
pocos cortes, que es el borde de toda formación vista desde abajo, el compuesto caía 0,166 por
debajo del brillo del cielo. En el núcleo, con los ocho cortes, los de arriba lo compensaban. Por eso
el defecto aparecía en los bordes y no en el medio.

**El nivel de mayor detalle era opaco.** Con alfa 0,55 por corte, ocho cortes daban una opacidad de
0,998. Un sistema pensado para dar volumen translúcido producía, en su mejor nivel, una pared.

**Cada cambio de nivel de detalle cambiaba el brillo.** 0,998 con ocho cortes, 0,959 con cuatro,
0,798 con dos: hasta 20 puntos de opacidad de golpe al cruzar un umbral de distancia. Es la causa
dominante de la "recarga" que se ve volando, y no estaba en ninguna lista de sospechosos.

Corregido con una sola fórmula: el alfa de cada corte se deriva de cuántos cortes hay, de modo que
la pila llegue siempre a 0,92. El nivel de detalle pasa a cambiar la estructura interna de la nube y
no su densidad aparente, que es lo que un LOD debe hacer. Más `BOTTOM_SHADE` de 0,62 a 0,78, que baja
la caída de 0,166 a 0,050 — una nube vista desde abajo tiene que seguir siendo algo más oscura que
el cielo, o se lee como niebla.

**`MINIMAL` no existía.** El tramo de `LOW` llegaba hasta el borde del domo, así que el cuarto nivel
de detalle era inalcanzable. Y era código muerto dañino: usaba celdas de 64 bloques, el tamaño que
producía las sábanas rectangulares que la 0.0.1 había corregido. Eliminado, con una prueba que
verifica que todos los niveles declarados sean alcanzables.

**Prefetch direccional.** `MotionPrefetch` proyecta la posición de la cámara 1,5 segundos hacia
adelante y esa posición decide qué se genera y con qué prioridad; la real sigue decidiendo qué se
dibuja. Caminando no adelanta nada, con elytra adelanta 67 bloques, y un teletransporte no adelanta
nada — la primera versión sí lo hacía, acotado a 256 bloques en una dirección sin sentido, y lo
delató una prueba.

## Limitaciones conocidas

- **Orden de blending dentro de una región.** Los slices se hornean de abajo hacia arriba en un
  solo buffer. Visto desde arriba, el orden de mezcla es el inverso al correcto. Como todos los
  slices de una capa comparten color y solo difieren en sombreado, el artefacto es sutil, pero
  existe. La solución limpia —dos índices por malla, o rehornear al cruzar la capa— quedó fuera
  hasta que alguien pueda ver si molesta de verdad.
- **El render target a media resolución no está implementado.** Era la Verificación 4 de la Fase 0
  y sigue sin verificar, así que las nubes se dibujan a resolución completa. Si el fill rate
  resulta ser el cuello de botella —que es lo probable—, esta es la primera optimización a probar.
- **El ajuste de nubes del juego queda cambiado mientras Atmosia está activo.** Es el precio de
  suprimir vanilla sin mixin. Se restaura al desactivarse, pero un cierre abrupto del juego puede
  dejarlo en OFF. Además, mientras Atmosia dibuja, cambiar ese ajuste desde el menú de opciones de
  Minecraft no tiene efecto: Atmosia lo vuelve a apagar al tick siguiente. El interruptor que manda
  es el del menú del mod.
- **Detección de shader packs por presencia del mod, no por pack cargado.** Es conservador: con
  Iris u Oculus instalados Atmosia se desactiva aunque no haya pack activo. Afinarlo requiere la
  API de Iris, que no es estable entre versiones.
- **Los valores numéricos son estimaciones.** Alturas de capa, cobertura, umbrales, opacidad por
  slice y tamaño de región se eligieron por criterio, no por haberlos visto en pantalla. Es lo
  primero que va a querer tocarse.
