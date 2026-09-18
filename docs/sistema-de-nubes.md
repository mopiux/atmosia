# El sistema de nubes de Atmosia

*Cómo se generan y se dibujan las nubes hoy, con sus ventajas y sus costos.*

*Estado: versión 0.2.0. Los números de rendimiento salen de la tanda del 18/09/2026 a las 16:40, la primera medida con las nubes vanilla efectivamente apagadas.*

---

## 1. La idea en una frase

Atmosia no modela nubes. Modela un **campo de densidad en dos dimensiones** y lo convierte en volumen apilando planos horizontales translúcidos, cada uno recortado con un umbral distinto.

Esa frase contiene toda la arquitectura, y también todos sus límites. El resto del documento la desarma.

---

## 2. La decisión de fondo: por qué no hay volumen de verdad

Hay tres formas conocidas de dibujar nubes volumétricas en tiempo real:

| Técnica | Cómo funciona | Por qué no se usó |
|---|---|---|
| **Ray marching** | Por cada píxel se avanza paso a paso dentro de un volumen 3D acumulando densidad. | Es lo que usan los juegos con nubes espectaculares. Exige un shader propio y cuesta por píxel de pantalla: en una placa modesta, a 1080p, son decenas de millones de muestras por frame. El documento de diseño lo descartó explícitamente. |
| **Voxels** | Se guarda densidad en una grilla 3D y se construye una malla. | La memoria crece con el cubo de la distancia. Un domo de 1.000 bloques con celdas de 8 sería inmanejable. |
| **Slices apilados** | Se guarda densidad en 2D y se dibuja como N planos horizontales translúcidos. | **Es lo que hace Atmosia.** |

La apuesta es que el ojo humano lee "volumen" a partir de dos pistas baratas: **silueta que cambia con la altura** y **opacidad que se acumula**. Si los planos de arriba y de abajo son más chicos que los del medio, el conjunto se lee como algo redondeado. No es un volumen; es una ilusión de volumen construida con geometría plana.

La consecuencia buena: el costo es proporcional al área cubierta, no al volumen, y no hace falta ningún shader propio.

La consecuencia mala: **es una ilusión con un ángulo de visión privilegiado**. Funciona mirando hacia arriba o de costado. Mirando desde arriba en picada, los planos se ven como lo que son.

---

## 3. El recorrido completo, paso a paso

### 3.1 De ruido a densidad

La base es **ruido de valor fractal** (`NoiseField`): 4 octavas, lacunaridad 2,0, ganancia 0,5, interpolación hermite (`t²(3−2t)`).

No es simplex ni Perlin. Es el ruido más barato que da un resultado aceptable, y esa fue una decisión consciente: se evalúa una vez por celda por región, o sea decenas de miles de veces por segundo.

El hash trabaja sobre **coordenadas enteras de celda en `long`**, mezcladas estilo splitmix64, no sobre `float` de coordenadas absolutas. Eso importa más de lo que parece: a 10 millones de bloques del origen, un `float` de 32 bits ya no distingue entre puntos separados por un bloque, y el ruido se degradaría en escalones visibles. Con enteros, no.

El valor crudo del ruido se convierte en densidad recortando por **cobertura**:

```
floor   = 1 − cobertura_efectiva
densidad = (crudo − floor) / (1 − floor)     si crudo > floor
densidad = 0                                 si no
```

La cobertura **desplaza el umbral** en vez de escalar el resultado. Por eso subir la cantidad de nubes agranda las formaciones que ya existen en lugar de volver todo el cielo uniformemente más opaco. Es un detalle chico con un efecto visual grande.

### 3.2 El truco central: espacio de nube

Las nubes se mueven. La forma ingenua de hacerlo es regenerar la geometría con el viento aplicado, y es inviable: sería reconstruir el cielo entero cada frame.

Atmosia indexa las regiones en **espacio de nube**: coordenadas con el viento **ya descontado**.

```
cámara_en_espacio_de_nube = cámara_mundo − viento(t)
```

La densidad de una región **no cambia jamás**. Lo único que cambia con el tiempo es *dónde se dibuja*: una traslación en el momento del dibujo. Esto resuelve de una sola vez dos requisitos que parecían estar en conflicto — que las nubes se muevan y que la geometría no se regenere.

El tiempo viene del **tiempo del mundo**, no del reloj del cliente. Con el reloj del cliente las nubes saltarían al reconectar, se desincronizarían del ciclo día/noche y seguirían avanzando con el juego en pausa.

### 3.3 Regiones

El cielo se divide en cuadrados de **256×256 bloques**, por capa. Una región es la unidad de:

- cálculo de densidad (un trabajo en un hilo de fondo),
- construcción de malla (un `VertexBuffer` en GPU),
- caché y desalojo,
- culling contra el frustum,
- una draw call.

Las coordenadas dentro de una región son **locales a ella**, nunca absolutas. Es la otra mitad de la estrategia de floating origin.

### 3.4 Tres capas

| Capa | Altura base | Espesor | Cobertura | Velocidad X | Escala de ruido |
|---|---|---|---|---|---|
| `low` | 172 | 16 | 0,42 | 0,6 b/s | 300 |
| `mid` | 192 | 20 | 0,36 | 1,0 b/s | 420 |
| `high` | 216 | 14 | 0,26 | 1,6 b/s | 620 |

Las tres comparten el mismo campo de ruido con escalas y cortes distintos. Donde coinciden, la densidad se acumula visualmente y se lee como una masa más profunda en vez de como tres láminas superpuestas.

Las **velocidades distintas** son lo que produce paralaje: mirando el cielo un rato, las capas se separan solas. Es el efecto más barato y más convincente del sistema entero.

### 3.5 Nivel de detalle

| Nivel | Cortes por capa | Lado de celda | Capas dibujadas |
|---|---|---|---|
| `HIGH` | 8 | 16 | 3 |
| `MEDIUM` | 4 | 16 | 3 |
| `LOW` | 2 | 32 | 2 |
| `MINIMAL` | 1 | 64 | 1 |

El LOD acá **no es "menos vértices de la misma forma"**: es menos cortes y, solo en los niveles lejanos, celdas más grandes.

Ese detalle está aprendido a los golpes. La primera versión usaba celdas de 64 y 128 bloques en los niveles lejanos, y el resultado fueron sábanas rectangulares gigantes cruzando el cielo: a cualquier distancia, un cuadrado de 128 bloques se lee como un rectángulo, no como una nube. **El ahorro a distancia tiene que salir de los cortes, que es donde está el costo de relleno, no del tamaño de celda, que es donde está la forma.**

El domo llega hasta `max(512, render_distance × 16 × multiplicador)`. El piso de 512 también es una cicatriz: con el domo corto, su borde caía dentro del campo de visión y el cielo se veía recortado con una línea recta.

### 3.6 Cortes y umbral por altura

Acá es donde aparece el volumen. Cada corte de una capa usa un **umbral de densidad distinto**:

```
t      = (índice + 0,5) / cortes          → posición vertical normalizada
centro = |t − 0,5| × 2                    → 0 en el medio, 1 en los extremos
umbral = 0,06 + 0,62 × centro^1,6
```

El corte del medio acepta casi cualquier densidad (umbral 0,06). Los de los extremos exigen densidad alta (hasta 0,68). Por eso **solo el núcleo de una formación llega arriba y abajo**, y la silueta resultante es redondeada en vez de ser un prisma.

Cada celda que supera el umbral emite un cuádruple horizontal con:

- **alfa** = transición suave del borde (`(densidad − umbral) / 0,22`, saturado a 1), multiplicada por 0,55 si hay varios cortes o por 0,85 si hay uno solo;
- **sombreado** horneado en el color del vértice: los cortes bajos reciben un 62% de la luz de los altos, y las zonas más densas se oscurecen otro 12%.

La transición suave del borde es lo que evita que se vea como bloques. El sombreado por altura es lo que evita que se vea como una pared plana.

### 3.7 Concurrencia

El reparto es estricto y sale de dónde vive el contexto de OpenGL:

| Trabajo | Dónde corre | Por qué |
|---|---|---|
| Cálculo de densidad | Hilos de fondo | Es puro: recibe seed, capa y región, devuelve un arreglo. No toca Minecraft ni OpenGL ni comparte estado mutable. |
| Construcción de malla y subida a GPU | Hilo de render | Toca `BufferBuilder` y el contexto gráfico, que no es compartible. |
| Dibujo | Hilo de render | Idem. |

La parte cara y paralelizable está afuera; la parte que no se puede mover, adentro.

### 3.8 Presupuesto por frame

El renderer **no genera lo que haga falta**: genera lo que entra en el presupuesto del frame y deja el resto para los siguientes, respetando prioridad.

Las regiones pendientes se ordenan por clase de prioridad —visible cerca, visible medio, visible lejos, detrás del jugador, fuera de cámara— y dentro de cada clase por distancia. Lo que no entra hoy se reevalúa mañana, ya con la prioridad recalculada según dónde está mirando el jugador.

Los resultados de densidad que llegan y no entran en el presupuesto **no se tiran**: el cálculo ya está pagado, así que se guardan (hasta 32) y se construyen en el frame siguiente, antes que nada nuevo.

Esta es la razón estructural por la que el cielo se llena despacio en vez de tironear. El costo se paga en tiempo, no en un pico.

### 3.9 Culling con desvanecido

Dos mecanismos, y ninguno es un corte binario:

- **Vertical** (`VerticalFade`): estar dentro de una capa nunca atenúa. Más allá de 180 bloques de distancia vertical empieza a desvanecerse; a 420 ya es invisible. Mirar *hacia* la capa la sostiene visible hasta un 50% más lejos. Solo por debajo de opacidad 0,02 se descarta la geometría de verdad — de forma que el propio desvanecido tape el corte.
- **Distancia**: el último 15% del domo se desvanece, para que el borde no aparezca como una línea recta.

Además, cuando el LOD de una región cambia se encola la versión nueva **pero se sigue dibujando la vieja** hasta que esté lista. Sin eso, cruzar un umbral de distancia abría un agujero en el cielo.

### 3.10 Dibujo y transparencia

- Mezcla translúcida estándar.
- **Escribe color pero no profundidad.** Las nubes son volumen, no superficie: si escribieran profundidad, los cortes se ocultarían entre sí.
- **Lee** profundidad, así que las montañas y las construcciones las tapan correctamente.
- Sin cull de caras: los cortes son planos horizontales y se ven desde arriba y desde abajo.
- Sin textura y sin shader propio. El color va por vértice con el sombreado ya horneado, y el tinte del momento del día se aplica como uniforme al dibujar. Eso mantiene el mod en la ruta de renderizado estándar de Forge.
- Las regiones se dibujan **de lejos a cerca**, porque con transparencia el orden cambia el resultado.
- Hay un brillo extra cuando se mira hacia el sol: `1 + 0,35 × cos⁶`, con exponente alto para que el efecto aparezca solo mirando bastante de frente.

Se dibuja después de los bloques translúcidos y antes del clima: ya quedan ordenadas respecto del terreno, y la lluvia y la nieve siguen por delante.

### 3.11 Supresión de las nubes vanilla

Sin mixin. Atmosia **apaga el ajuste de nubes del propio juego** mientras dibuja, y lo restaura al desactivarse.

Es tosco, y es a propósito. El riesgo principal del proyecto nunca fue el rendimiento sino la compatibilidad: cualquier hook sobre `LevelRenderer` choca con shader packs y con mods de optimización. Esta vía no comparte punto de inyección con nadie.

Se llegó acá después de dos fracasos:

1. **Sustituir los efectos de dimensión** por unos con altura de nubes inválida, que es como el Nether y el End no dibujan nubes. No funcionó: `ClientLevel` resuelve sus efectos una sola vez, al construirse, y Atmosia se entera del mundo después.
2. **Apagar el ajuste una sola vez, al activarse.** Tampoco alcanzó. El menú de opciones lo reescribe al cerrarse, una recarga del archivo lo devuelve a su valor guardado, y cualquier otro mod que lo toque gana por ser el último.

La versión actual **lo reaplica una vez por tick** y cuenta cuántas veces hizo falta. Cuesta una comparación de enums por tick.

---

## 4. Qué dicen las mediciones

Tanda del 18/09 a las 16:40. Perfil Medio, cantidad 120%, render distance 16, 1920×991, **`cloud_status=off` en las cuatro corridas** — o sea, Atmosia sola, sin vanilla dibujando encima.

| Escenario | FPS medio | 1% low | CPU mediana | GPU mediana | GPU p95 |
|---|---|---|---|---|---|
| `fast_travel` | 69,2 | 11,9 | 11,96 ms | 2,69 ms | 10,21 ms |
| `altitude_sweep` | 66,5 | 16,1 | 13,57 ms | 1,77 ms | 9,36 ms |
| `full_coverage` | 34,0 | 18,2 | 28,61 ms | 16,11 ms | 23,68 ms |
| `camera_spin` | 30,2 | 13,6 | 32,27 ms | 12,59 ms | 22,53 ms |

### 4.1 El temporizador de GPU quedó arreglado

En la tanda anterior, `fast_travel` repitió el mismo valor de GPU durante **1263 frames seguidos** — el 72% de la corrida. Ahora la racha máxima de valores idénticos es de **1 o 2 frames** en las cuatro corridas. Las cifras de GPU vuelven a valer.

### 4.2 Hay dos regímenes distintos, y se ven en la distribución

`fast_travel` y `altitude_sweep` tienen una distribución de GPU **bimodal**: la mediana está en 1,8–2,7 ms, pero entre el 18% y el 24% de los frames cuestan 7–9 ms.

`full_coverage` y `camera_spin` **no son bimodales**: ningún frame supera el doble de la mediana. Son caros de forma pareja.

La lectura es clara. En los escenarios de movimiento, el costo son los **picos de subida de geometría a GPU**: uno de cada cinco frames construye regiones nuevas y paga por eso. En los escenarios de cielo lleno o de cámara girando, el costo es **relleno sostenido** — hay nubes ocupando toda la pantalla, todo el tiempo, y no hay pico porque no hay valle.

Son dos problemas distintos y se arreglan de formas distintas.

### 4.3 El cuello de botella no es el que parecía

En `camera_spin`, la CPU mediana es de 32,27 ms y la GPU de 12,59. **El frame está limitado por CPU, no por GPU**, y por un margen de 2,5 a 1. El 44,6% de los frames pasan de 33 ms.

Esto contradice la expectativa del documento de diseño, que apuntaba al fill rate como cuello probable. En el escenario que gira la cámara, el fill rate no manda.

**Advertencia importante:** el `cpu_ms` que se mide es el del frame completo, no el de Atmosia. Girar la cámara también obliga a Minecraft a rehacer su propio culling de chunks, que es caro y no tiene nada que ver con este mod. **Sin la línea base de vanilla no se puede separar una cosa de la otra**, y esa línea base todavía no se midió. Es la medición más valiosa que queda pendiente y se hace poniendo el modo de nubes en `Vanilla` y repitiendo los cuatro escenarios.

### 4.4 Los 1% low siguen siendo el problema

En `fast_travel` la media es de 69 FPS y el 1% low de 11,9. Es una diferencia de casi 6 a 1.

Eso no es "va lento": es **va bien y de golpe da un tirón**. El frame más lento de esa corrida fue de 166 ms. Un jugador nota mucho antes un tirón cada tanto que una caída pareja de 15 FPS.

El sospechoso número uno está identificado y es de diseño: `region_gen_ms` llegó a **1,26 ms** en esa corrida. La construcción de malla corre en el hilo de render, y aunque el presupuesto limita cuántas regiones entran por frame, cuando entran se pagan ahí mismo.

---

## 5. Lo bueno

**Es barato donde tiene que serlo.** Sin ray marching, sin shader propio, sin volumen en memoria. Dos de las cuatro corridas están cómodamente por encima de 60 FPS.

**El movimiento es gratis.** Indexar en espacio de nube convierte la animación en una traslación. La densidad de una región no se recalcula nunca, y eso es lo que permite tener un domo grande con un presupuesto chico.

**No tironea por diseño, sino por accidente.** El presupuesto por frame garantiza estructuralmente que ninguna actualización congele el juego. Los tirones que quedan vienen de la construcción de malla, que es un punto concreto y arreglable, no de una decisión arquitectónica.

**Es determinista y estable lejos del origen.** Misma seed y mismas coordenadas dan siempre lo mismo. El hash entero evita la degradación a millones de bloques.

**No pelea con nadie.** No toca `LevelRenderer`, no registra shaders, no usa mixins. Si hay un mod de shaders instalado, Atmosia cede el cielo en vez de disputarlo.

**El paralaje entre capas funciona.** Tres velocidades distintas dan sensación de profundidad por casi nada de costo.

**El núcleo es verificable de verdad.** Todo el procedimiento —ruido, densidad, LOD, fades, prioridad, presupuesto— está deliberadamente libre de dependencias de Minecraft. Se compila y se corre con `javac` y `java` a secas: 93 comprobaciones. Eso encontró tres errores reales antes de que el código llegara al juego, incluido uno donde el 1% low se calculaba mal y reportaba 100 FPS en una corrida con un tirón de 100 ms.

---

## 6. Lo malo

**El ángulo privilegiado.** La ilusión de volumen se rompe mirando desde arriba en picada. Ahí los cortes se ven como los planos que son. Es una limitación de la técnica, no un error a corregir.

**El orden de mezcla dentro de una región es el inverso vista desde arriba.** Los cortes se hornean de abajo hacia arriba en un solo buffer. Como todos comparten color y solo difieren en sombreado, el artefacto es sutil — pero existe. La solución limpia (dos índices por malla, o rehornear al cruzar la capa) quedó fuera.

**Los tirones.** 1% low de 11,9 FPS con una media de 69. Ver §4.4.

**El relleno con cielo lleno.** `full_coverage` cae a 34 FPS con 16 ms de GPU mediana. La optimización prevista para esto —dibujar las nubes a media resolución— **está sin implementar**. Era la Verificación 4 de la Fase 0 y sigue pendiente.

**El ajuste de nubes del juego queda cambiado.** Es el precio de suprimir vanilla sin mixin. Se restaura al desactivarse, pero un cierre abrupto puede dejarlo en OFF. Y mientras Atmosia dibuja, cambiar ese ajuste desde el menú de Minecraft no tiene efecto: se vuelve a apagar al tick siguiente.

**Las nubes son de cliente y no están sincronizadas.** La seed se deriva de la dirección del servidor o del nombre de la dimensión, porque en un servidor remoto el cliente no conoce la seed del mundo. Dos jugadores del mismo servidor no ven necesariamente las mismas nubes. Es una decisión consciente, no un error, y se resuelve fijando la misma seed a mano.

**Detección de shader packs por presencia del mod, no por pack cargado.** Con Iris u Oculus instalados, Atmosia se desactiva aunque no haya ningún pack activo. Es conservador a propósito: prefiere no dibujar a dibujar mal.

**El ruido es barato y se nota.** Ruido de valor de 4 octavas no produce los bordes con jirones de las nubes reales. Las formaciones tienen contornos más redondeados y más parejos de lo que deberían.

**Casi todos los números son estimaciones.** Alturas, coberturas, umbrales, opacidad por corte, tamaño de región: se eligieron por criterio, sin ver una sola imagen. Es lo primero que va a querer afinarse ahora que por fin hay capturas.

**Falta la línea base.** Sin medir vanilla en los mismos escenarios, ninguna de las cifras de arriba se puede atribuir con certeza a Atmosia.

---

## 7. Qué hay que hacer, en orden

1. **Medir la línea base de vanilla.** Cuesta dos minutos y es la que le da sentido a todo lo demás. Modo de nubes en `Vanilla`, mismos cuatro escenarios.
2. **Atacar los 1% low.** Construcción de malla incremental, o un presupuesto por microsegundos en vez de por cantidad de regiones.
3. **Render target a media resolución.** Es la respuesta directa a `full_coverage`, y está especificada pero sin escribir.
4. **Ver de dónde sale el costo de CPU de `camera_spin`**, una vez que la línea base diga cuánto es de Minecraft y cuánto nuestro.
5. **Afinar los valores estéticos** con las capturas en la mano.

---

*Referencias: `docs/fase-0-analisis-y-arquitectura.md` para las decisiones y sus alternativas descartadas; `docs/fases.md` para el estado por fase; `docs/benchmark.md` para cómo correr las mediciones.*
