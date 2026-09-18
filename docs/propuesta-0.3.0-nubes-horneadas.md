# Atmosia — Nubes horneadas a textura (propuesta para 0.3.0)

*Documento de diseño para implementación. Dirigido a quien vaya a escribir el código, no a quien lo lea como reporte de estado.*

*Parte de `docs/sistema-de-nubes.md`, que describe el sistema vigente en **0.2.1**. No repite lo que ya funciona: describe qué cambia, por qué, y en qué orden.*

---

## 0. Sobre las versiones

| | |
|---|---|
| **Sistema vigente** | 0.2.1 (`6a62e2d`, rama `version-0.2.1`) |
| **De dónde salen las mediciones** | 0.2.0 (`7f02ed0`, rama `version-0.2.0`) — la build que corrió los benchmarks del 18/09 a las 16:40 |
| **Versión propuesta** | 0.3.0 |

La 0.2.1 no cambió nada del renderer respecto de la 0.2.0: solo el arreglo del `results.csv` y documentación. Las mediciones de la 0.2.0 describen exactamente el comportamiento de la 0.2.1.

**0.3.0 es el número correcto para esto** según el esquema que ya está en uso: la 0.2.1 subió un dígito menor porque no tocó el renderer, y esto lo reemplaza entero.

El plan por etapas del §7 no se publica como una sola versión. Las etapas de prototipo conviven con el sistema viejo detrás de una opción de configuración y salen como **0.2.x**; **0.3.0 es el momento en que el sistema horneado pasa a ser el predeterminado** y el de cortes se retira. Si el §7 se corta a la mitad porque las mediciones no confirman la hipótesis, no queda una 0.3.0 rota: queda una 0.2.x con un experimento apagado por defecto.

---

## 1. El cambio en una frase

Reemplazar la generación de **geometría dinámica por celda** —cuádruples por corte, construidos en el hilo de render— por **rasterizado del campo de densidad a una textura por región**, dibujada como vanilla: un quad, una textura, alfa por texel.

El ruido, las regiones, las capas, el presupuesto por frame y la cola de prioridad **no cambian**. Cambia únicamente qué se construye con el resultado del ruido: antes una malla, ahora una imagen.

---

## 2. Por qué: motivación medida, no intuida

De la tanda del 18/09, con las nubes vanilla ya suprimidas:

- `region_gen_ms` llegó a **1,26 ms** en `fast_travel`, y corre en el hilo de render. Es el sospechoso identificado de los 1% low: 11,9 FPS contra una media de 69,2.
- `full_coverage` cae a 34 FPS con **16,11 ms de GPU mediana**. Relleno sostenido: 4 a 8 cuádruples translúcidos apilados por celda.
- Los dos síntomas comparten raíz: el costo escala con la cantidad de geometría, no con el área de pantalla cubierta.

Hay además un dato del análisis frame a frame que refuerza la hipótesis y que conviene tener presente:

> En `fast_travel` y `altitude_sweep` la distribución de GPU es **bimodal** —mediana de 1,8–2,7 ms con el 18–24% de los frames en 7–9 ms—, mientras que en `full_coverage` y `camera_spin` no hay un solo frame por encima del doble de la mediana.

Eso dice que son **dos problemas distintos**: picos de construcción y subida en movimiento, relleno sostenido con el cielo lleno. Esta propuesta ataca los dos, pero por caminos distintos, y hay que medirlos por separado. Un promedio agregado los taparía a ambos, que es justo lo que pasó en la primera tanda.

Vanilla no sufre ninguno de los dos porque no genera geometría por celda: lee una textura fija y dibuja un plano. La apuesta es tomar esa propiedad y aplicarla al *resultado* del sistema de densidad de Atmosia, sin tocar cómo se genera ese resultado.

---

## 3. Arquitectura resultante

```
NoiseField (sin cambios)
   → DensityField / DensityJob                 [hilo de fondo, cambia la resolución de muestreo]
   → RegionBaker (NUEVO)                       [hilo de fondo]
       rasteriza densidad + umbral + sombreado → RGBA8 por texel, por sub-plano
   → subida de textura a GPU                   [hilo de render, reemplaza a VertexBuffer.upload]
   → 2-3 quads texturados por capa por región  [hilo de render, reemplaza a N cuádruples por corte]
```

**Corrección de nombres respecto del borrador:** la densidad no la calcula `NoiseField` sino `DensityJob`, que usa `DensityField` (que a su vez consulta `NoiseField`). `NoiseField` es solo la función de ruido. Importa porque el `RegionBaker` va a convivir con `DensityJob`, no con `NoiseField`.

**Sobre los sub-planos.** El borrador proponía primero un único quad plano por capa y se corrigió a 2–3 sub-planos. La corrección es acertada y hay que sostenerla: un solo plano recrea la silueta *horizontal* vía alfa acumulado, pero en ángulo rasante vuelve a ser una lámina sin espesor — el defecto de vanilla, que es justo lo que este rediseño quiere superar.

---

## 4. Las dos cosas que el borrador no presupuestó

Esta sección es nueva. Son los dos costos que el diseño propuesto introduce y que no aparecían en ningún lado, y **cualquiera de los dos puede invalidar la propuesta si no se acota**.

### 4.1 El costo de ruido se multiplica

Una región mide 256 bloques. Las muestras de densidad de hoy y las que pediría cada resolución de textura:

| | Muestras por región-capa | Bloques por muestra |
|---|---|---|
| **Hoy, `HIGH`** (celdas de 16) | 16 × 16 = **256** | 16 |
| Textura 32×32 | 1.024 | 8 |
| Textura 64×64 | 4.096 | 4 |
| Textura 128×128 | 16.384 | 2 |

La tabla de LOD del borrador pedía **128×128 en `HIGH`: 64 veces más evaluaciones de ruido que hoy**, con 4 octavas cada una. No es un detalle de afinado; es el costo dominante del sistema nuevo.

Peor todavía: el borrador pedía **16×16 en `MINIMAL`**, que es exactamente la resolución que hoy tiene `HIGH`. O sea que las nubes más lejanas del nivel más barato tendrían el detalle de las más cercanas de hoy. La tabla entera está corrida hacia arriba.

El borrador propone esquivarlo **interpolando** el arreglo de densidad grueso hasta la resolución de textura. Eso evita el costo pero no genera detalle: interpolar 16×16 a 128×128 es inventar 64 texels por cada dato real. El contorno resultante sería notablemente más blando que el de hoy, y la transición de borde (`/0,22`) se estiraría sobre 8 texels en vez de definir un filo. **Se paga en lo visual justo lo que el rediseño quiere mejorar.**

Y el argumento que da el borrador para interpolar —"mantener la fila «NoiseField sin cambios» cierta en el sentido estricto"— no se sostiene: `NoiseField` es una función pura. Llamarla más veces no la cambia. La fila sigue siendo cierta con cualquiera de las dos opciones.

**Resolución propuesta:** muestrear el ruido a razón de texel, sin interpolar, y bajar la tabla de resoluciones a algo que el presupuesto aguante (§6). El punto medio razonable es `HIGH` a 64×64 — 4 bloques por texel, **4 veces más fino que hoy** y 16 veces más caro en ruido. El paso 2 del §7 tiene que medir exactamente esto antes de seguir.

Hay una compensación real que hace que la apuesta valga: ese costo está en **hilos de fondo**, mientras que lo que se elimina —la construcción de malla— está en el **hilo de render**. Aun si el trabajo total sube, mover trabajo fuera del hilo de render es la dirección correcta. Pero solo si las regiones siguen llegando a tiempo, y eso hay que medirlo, no suponerlo.

### 4.2 La memoria de GPU se multiplica por cien

Hoy el CSV reporta un `gpu_bytes` de **0,25 a 0,29 MB**. Con texturas, y la caché llena:

| Perfil | Regiones en caché | 32×32 | 64×64 | 128×128 |
|---|---|---|---|---|
| Bajo | 128 | 1,5 MiB | 6 MiB | 24 MiB |
| Medio | 384 | 4,5 MiB | **18 MiB** | 72 MiB |
| Alto | 768 | 9 MiB | 36 MiB | **144 MiB** |

(RGBA8, tres sub-planos por región.)

Los 144 MiB del perfil Alto con la tabla del borrador no son inaceptables en una placa moderna, pero sí son un cambio de categoría que hay que declarar. Y el perfil **Bajo existe precisamente para placas modestas**: 24 MiB de texturas de nubes en una placa de 2 GB compartidos es una decisión que hay que tomar a propósito, no descubrir después.

Con `HIGH` a 64×64, el perfil Medio queda en 18 MiB. Es el orden de magnitud correcto para el resultado que se busca.

**Consecuencia de diseño:** el pool de texturas del §5.2 deja de ser una optimización y pasa a ser un requisito. Sin pool, el pico de memoria es impredecible; con pool, es exactamente el número de la tabla y se puede dimensionar contra el perfil.

---

## 5. Paso a paso de implementación

### 5.1 `RegionBaker`: rasterizar densidad a textura

Clase nueva, junto a `DensityJob`, en el mismo pool de hilos. Por cada región × capa:

1. **Muestrear el ruido a razón de texel** (§4.1). `DensityJob` ya hace exactamente esto, solo que a razón de celda: hay que parametrizar la resolución, no escribir un muestreo nuevo.
2. Para cada texel de la textura `N×N`, **y por cada uno de los 2–3 sub-planos**:
   - Aplicar el umbral de altura de *ese* sub-plano. No acumulado entre sub-planos: cada uno hornea su propio alfa, igual que hoy cada corte tiene el suyo.
   - `alfa = clamp((densidad − umbral) / 0,22, 0, 1)`.
   - El sombreado por altura, hoy horneado por vértice, va al RGB del texel ya resuelto.
3. Escribir a un buffer RGBA8 por sub-plano. Puro cómputo, sin tocar OpenGL.

**Sobre el `clamp`:** el borrador lo marca como "obligatorio y explícito, no implícito en la conversión a byte". La observación es correcta como principio, pero conviene saber que **el código actual ya lo hace**: `DensityField.cellAlpha` cierra con `Math.min(1.0D, over)`. No hay un error que arreglar acá; hay una propiedad que no hay que perder al mover el cálculo.

**Reutilizar el núcleo, no reescribirlo.** `sliceThreshold`, `cellAlpha` y `shade` ya existen en `DensityField`, ya están cubiertos por las 93 comprobaciones y no dependen de si el destino es un vértice o un texel. El `RegionBaker` los llama; no los reimplementa.

### 5.2 Subida a GPU

Donde hoy hay `BufferBuilder` + `VertexBuffer.upload()`, va la subida de textura.

**Adaptación a 1.20.1:** el borrador propone `glTexImage2D` y Pixel Buffer Objects. Los PBO **no están expuestos por Blaze3D**, así que usarlos obliga a bajar a LWJGL crudo — y eso contradice directamente la propiedad que el §6 dice conservar ("mantener el mod en la ruta de renderizado estándar de Forge"), que es la que evita los choques con shader packs y mods de optimización. Es el riesgo número uno del proyecto según la Fase 0.

La ruta idiomática es `NativeImage` + `DynamicTexture`, cuyo `upload()` hace `glTexSubImage2D` sobre una textura ya alocada. No es asíncrono, pero es predecible en costo, que es lo que el §2 pide: el problema de hoy no es que subir cueste, es que cueste **una cantidad variable** según cuántas celdas superaron el umbral.

Empezar por ahí. Si la medición del §7.3 muestra que la subida síncrona sigue siendo el cuello, ahí se discute PBO con el costo de compatibilidad sobre la mesa.

**Pool de texturas, no crear y destruir.** Por §4.2 esto es requisito, no optimización. Una región que sale de caché devuelve su textura al pool; una que entra la toma de ahí. Todas las texturas de un mismo nivel son intercambiables. El pool se dimensiona contra `maxCachedRegions` del perfil activo, que ya existe en `QualityProfile.Settings`.

Sin pool, crear y destruir texturas al entrar y salir regiones repite el mismo problema que causó los 1% low de la 0.2.0, con otro nombre.

### 5.3 Dibujo

- Formato de vértice: `DefaultVertexFormat.POSITION_TEX_COLOR` con `GameRenderer.getPositionTexColorShader()`. **Existe en 1.20.1 y no requiere registrar un shader propio**, así que la propiedad de compatibilidad se conserva intacta. Es importante decirlo explícitamente: era la preocupación central de la Fase 0.
- El tinte del momento del día sigue aplicándose como uniforme, igual que hoy.
- Sin cambios: mezcla translúcida estándar, escribe color pero no profundidad, lee profundidad, sin cull de caras. Ninguna de esas reglas depende de si la fuente es vértice o textura.
- Sub-planos de abajo hacia arriba, regiones de lejos a cerca.

**Sobre el mipmapping.** El borrador acierta al señalar que minimizar una textura con alfa suave adelgaza el borde de las nubes a distancia, y que hay que renormalizar la cobertura por nivel. Vale agregar que `MipmapGenerator` de Minecraft está pensado para texturas de bloques y no hace esa corrección, así que los niveles hay que generarlos a mano.

### 5.4 LOD: resolución en vez de cortes

Tabla corregida por §4:

| Nivel | Textura por sub-plano | Bloques/texel | Sub-planos | Capas | vs. hoy |
|---|---|---|---|---|---|
| `HIGH` | 64×64 | 4 | 3 | 3 | 4× más fino |
| `MEDIUM` | 32×32 | 8 | 2 | 3 | 2× más fino |
| `LOW` | 16×16 | 16 | 2 | 2 | igual que `HIGH` de hoy |
| `MINIMAL` | 8×8 | 32 | 1 | 1 | la mitad de fino |

Frente a la del borrador (128/64/32/16), esta baja un escalón entera. Mantiene la mejora de detalle donde se ve —cerca— y evita que el nivel más barato cueste lo que hoy cuesta el más caro.

**La lección que se traslada.** La 0.0.1 aprendió a los golpes que agrandar la *celda* arruina la silueta a distancia: celdas de 64 y 128 bloques se leían como rectángulos. El equivalente acá es bajar la resolución de textura demasiado rápido. El piso de 8×8 son 32 bloques por texel — con interpolación bilineal de la GPU, que las celdas de 32 de hoy no tienen. Aun así es el número a vigilar en las capturas del §7.

**Interacción con los perfiles gráficos.** `QualityProfile` limita hoy el detalle con `detailCap`, un `LodLevel` mínimo: el perfil Bajo usa `MEDIUM` como tope, o sea menos cortes. Con texturas eso sigue funcionando sin cambios de forma —el tope pasa a significar "menos resolución"— pero hay que revisar que la relación entre perfiles siga teniendo sentido, porque el eje que el tope recorta ya no es el mismo. Es un ajuste de valores, no de estructura.

### 5.5 Presupuesto por frame y cola de prioridad

No tocar. `CloudBudget` y `RegionPriority` ya resuelven el problema de mover trabajo a los frames siguientes. Lo único que cambia es qué se encola: antes "construir esta malla", ahora "hornear y subir esta textura".

Un detalle que sí hay que revisar: `CloudBudget` mide el presupuesto en **cuádruples**, y con texturas ya no hay cuádruples variables. La unidad natural pasa a ser texels subidos, o directamente regiones. El presupuesto de `quadsPerFrame` en `QualityProfile.Settings` se renombra y se recalibra; la lógica de la clase no cambia.

**La tentación a evitar** es escribir un sistema de colas nuevo "porque ahora es textura". Es el mismo problema ya resuelto.

### 5.6 Transición de LOD sin popping

La regla actual —seguir dibujando la región vieja hasta que la nueva esté lista— se mantiene. Mejora posible: **crossfade** entre las dos texturas durante 2–3 frames en vez de un corte. Con mallas hubiese sido caro; con texturas es un segundo sampler y un `mix()`.

Ojo con una consecuencia: el crossfade necesita las dos texturas vivas a la vez, lo que suma al pico del pool del §4.2. Es poco, pero hay que contarlo al dimensionarlo.

---

## 6. Qué NO cambia

Explícito, para que no se toque por error:

- `NoiseField`: hash en `long`, splitmix64, 4 octavas, lacunaridad 2,0, ganancia 0,5.
- Coordenadas de región locales — floating origin.
- Espacio de nube para el movimiento. La textura horneada se traslada al dibujar, igual que hoy se traslada la malla.
- Tres capas con paralaje. Y agregar una cuarta se vuelve casi gratis.
- `VerticalFade` y desvanecido de distancia. **Corrección al borrador:** hoy no se aplican "por vértice" sino como uniforme, vía `RenderSystem.setShaderColor`. O sea que no cambian en absoluto — no hay nada que adaptar acá.
- `CloudMode` (Atmosia / Vanilla / Ninguna) y la supresión del ajuste de nubes por tick. Sin relación con esto, y el modo `Vanilla` es la herramienta de medición del §7.1.
- `coverageScale`. Sigue afectando la densidad y por lo tanto lo horneado, así que **vaciar la caché al cambiarlo sigue siendo obligatorio**, con la misma lógica de descartar resultados en vuelo que ya existe.
- Las 93 comprobaciones del núcleo puro. El `RegionBaker` **se suma** a ese conjunto: es puro cómputo sobre arreglos, sin Minecraft, así que es tan verificable como el resto. No hay excusa para que entre sin pruebas.

---

## 7. Orden de trabajo

**No paralelizar los pasos 2 a 4.** Medir en cada uno evita construir tres etapas sobre una hipótesis que resultó falsa en la primera.

1. **Medir la línea base de vanilla.** Sigue pendiente desde la 0.2.0 y sigue siendo el paso cero. Modo de nubes en `Vanilla`, los cuatro escenarios. Sin eso no se puede afirmar qué parte del costo es de Atmosia.
2. **`RegionBaker` para una sola capa y solo `HIGH`** — a propósito el nivel con más sub-planos, que es donde más se nota si se perdió espesor. Validar en tres niveles:
   - **Numérico**, contra el núcleo existente: el alfa horneado de cada sub-plano debe coincidir con el alfa por corte equivalente, dentro de tolerancia.
   - **De presupuesto** (§4.1): cuánto sube el tiempo de densidad por región al muestrear a razón de texel, y si las regiones siguen llegando a tiempo. Este es el que puede matar la propuesta, así que va temprano.
   - **Visual**, con una captura **en ángulo rasante** comparando 0.2.1 contra el prototipo. La validación numérica no detecta pérdida de espesor: eso solo se ve en pantalla.
3. **Integrar subida y dibujo** para ese caso. Medir `region_gen_ms` y GPU en `full_coverage` contra la 0.2.1, mismo escenario, mismo perfil, misma cantidad. El CSV ya registra `quality_profile` y `coverage_scale`, así que la comparación queda documentada sola.
4. **Si los números confirman el §8**, extender a todos los niveles y las tres capas.
5. **Crossfade** del §5.6.
6. **Afinar lo estético** con capturas: resolución por nivel, curva de umbral, cantidad de sub-planos.

Durante los pasos 2 a 5 el sistema nuevo convive con el viejo detrás de una opción de configuración, y se publica como 0.2.x. **La 0.3.0 es el paso 4 confirmado**, con el sistema horneado por defecto.

---

## 8. Beneficios esperados

| Aspecto | 0.2.1 (cortes) | 0.3.0 (horneado) | Por qué |
|---|---|---|---|
| 1% low en movimiento | 11,9 FPS (`fast_travel`) | Mejora esperada | Subir una textura de tamaño fijo es predecible; construir una malla de tamaño variable no |
| GPU en `full_coverage` | 16,11 ms mediana | Mejora esperada | Desaparece el overdraw de 4–8 cuádruples apilados por celda; quedan 2–3 pases por capa |
| Costo a distancia | Constante por cantidad de cortes | Reducido | Mipmapping, que en el sistema de cortes no existe |
| Detalle cerca | 16 bloques por muestra | 4 bloques por muestra | La textura permite resolución más fina que la celda de geometría |
| Agregar una capa | Lineal en cuádruples | Casi gratis | Un quad y una textura más |
| Transición de LOD | Dibuja la vieja hasta que la nueva esté | Crossfade | Dos texturas se mezclan en el shader sin costo geométrico |
| **Costo de ruido** | 256 muestras por región-capa | **4.096** (16×) | Muestrear a razón de texel. En hilos de fondo, no en el de render |
| **Memoria de GPU** | 0,25 MB | **18 MiB** (perfil Medio) | RGBA8 por región × capa × sub-plano. Requiere pool |
| Riesgo visual | — | Pérdida de espesor si se usa un solo plano | Mitigado con 2–3 sub-planos reales (§5.4) |

Las dos filas en negrita son costos, no beneficios, y están ahí a propósito: son los que el borrador no listaba.

---

## 9. Cómo se sabe si esto fracasó

Por si el §8 no se cumple, los criterios de abandono, decididos antes de empezar:

- **El paso 2 mide un tiempo de densidad por región que no entra en el presupuesto** y no hay resolución que sirva a la vez para lo visual y para el costo → la propuesta no aplica a este sistema de ruido. Alternativa: caché de densidad compartida entre sub-planos y niveles, que hoy no existe.
- **El paso 3 no mejora `region_gen_ms`** → el cuello no era la construcción de malla. Hay que volver al perfilado antes de seguir, porque la hipótesis central del §2 era esa.
- **El paso 3 no mejora la GPU de `full_coverage`** → el cuello es el área cubierta y no el overdraw, y lo que corresponde es el render target a media resolución, que sigue sin implementar desde la Fase 0.
- **La captura en ángulo rasante del paso 2 se ve peor** que la 0.2.1 y no se arregla con más sub-planos → el ahorro no vale el costo visual, que es el criterio que ya se aplicó una vez al rechazar las celdas grandes.

Cualquiera de los cuatro se detecta en los pasos 2 y 3, antes de haber tocado el sistema de cortes. Ese es el motivo de no paralelizar.
