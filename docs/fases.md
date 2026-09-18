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
la supresión arreglada, ahora se puede: basta poner `enabled=false` en la configuración y correr los
mismos escenarios.

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
  dejarlo en OFF.
- **Detección de shader packs por presencia del mod, no por pack cargado.** Es conservador: con
  Iris u Oculus instalados Atmosia se desactiva aunque no haya pack activo. Afinarlo requiere la
  API de Iris, que no es estable entre versiones.
- **Los valores numéricos son estimaciones.** Alturas de capa, cobertura, umbrales, opacidad por
  slice y tamaño de región se eligieron por criterio, no por haberlos visto en pantalla. Es lo
  primero que va a querer tocarse.
