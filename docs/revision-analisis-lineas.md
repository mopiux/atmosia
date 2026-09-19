# Revisión del análisis externo sobre las líneas rectas

*Respuesta punto por punto a `analisis-causas-lineas-rectas.md`, un diagnóstico hecho con Claude Sonnet sobre el código de la 0.2.4.*

*Este documento no propone cambios ni toca código. Verifica las afirmaciones del análisis contra el código real, contra las mediciones que ya existían y contra la cronología del repositorio, y reordena las hipótesis según lo que esa verificación deja en pie.*

---

## Veredicto en una página

El documento analizado es bueno: está bien organizado, separa las hipótesis por probabilidad en vez de apostar a una sola, y propone pruebas que no requieren tocar código. Tres de sus observaciones son correctas y una de ellas señala un hueco real que yo había dejado abierto.

Pero tiene un problema de base y un error técnico concreto.

| | |
|---|---|
| **El problema de base** | Toda la estructura del documento cuelga de que el bug *persiste* en la 0.2.4. No se sabe. El video que analiza es anterior al arreglo, y nadie probó la 0.2.4 todavía. |
| **El error técnico** | Su hipótesis número uno describe un mecanismo —opacidad que se acumula a lo largo del recorrido del rayo dentro de una celda— que no puede existir en planos de espesor cero. |
| **Lo que rescata** | La crítica a la cobertura de los tests es correcta y se acepta entera. El cambio a interpolación quíntica es un punto real. El hueco del lado de celda 16→32 entre niveles es un hallazgo genuino. |
| **Lo que le falta** | El dato que ya teníamos medido —crestas, no escalones— contradice su hipótesis principal. Y no usa el único discriminante que separa las familias de causas de una sola vez: el espaciado de las líneas. |

---

## 1. La cronología: el video es anterior al arreglo

El análisis se apoya en esta frase, que es la bisagra de todo el resto:

> *"Si el síntoma persiste en 0.2.4 con esa causa ya cerrada, lo más lógico es que exista más de un mecanismo generando el mismo tipo de banda."*

**El síntoma no se sabe si persiste en la 0.2.4.**

El video analizado es el que provocó el arreglo de la escalera de alturas, no una prueba posterior a él. La secuencia en el repositorio es:

| Orden | Qué |
|---|---|
| 1 | Se graba el video y se toman los benchmarks (`frames-fast_travel-20260919-004945`) |
| 2 | Se entregan junto con el reporte de que el bug seguía presente |
| 3 | Se hace el análisis de medición y se identifica la costura de LOD |
| 4 | **Commit `ff99077`** — la escalera de alturas compartida (0.2.4) |
| 5 | El mod no se volvió a probar |

El análisis externo leyó el **código de la 0.2.4** y miró **fotogramas de la 0.2.3**. Es un cruce razonable si no se tiene la cronología a la vista, pero invalida la premisa de la que cuelga el resto del razonamiento.

### 1.1 Y la evidencia disponible la explica la causa ya corregida

Hay algo que agrava el problema anterior. La medición que se hizo sobre esos mismos fotogramas (Método 9 del inventario) dio:

> Residuos de entre **3 y 8 niveles de gris**, con anchos de **4 a 6 píxeles**.

La causa que ya se corrigió predecía exactamente eso. El alfa acumulado en la costura subía de 0,92 a 0,994: un **+8 % de opacidad en una banda fina, larga y recta**. Poco contraste, forma de cresta, unos pocos píxeles de ancho.

O sea: **la evidencia visual que tenemos queda completamente explicada por la causa que ya cerramos**. Construir una teoría de causas múltiples sobre esa misma evidencia, sin una prueba posterior al arreglo, es adelantarse.

---

## 2. El error técnico: la hipótesis 3.1 no puede funcionar así

Es la hipótesis que el documento pone primera, y el mecanismo que describe no existe en este renderer.

> *"El 'largo óptico' que recorre el rayo de vista dentro de la celda antes de salir de su rectángulo crece muchísimo respecto de mirarla de frente. Eso significa que, aunque el alfa por corte sea bajo, la opacidad acumulada a lo largo de ese tramo del rayo puede ser mucho más alta..."*

Eso es cierto en **ray marching sobre un volumen**, donde la opacidad se integra a lo largo del recorrido y el ángulo de entrada cambia cuánto medio atraviesa el rayo.

Acá los cortes son **planos horizontales de espesor cero**. Un rayo cruza un plano exactamente una vez, venga del ángulo que venga, y la mezcla aporta exactamente el alfa de esa celda. No hay recorrido interno que integrar.

**La opacidad acumulada de la pila es 0,92 mirando derecho hacia arriba y 0,92 mirando al ras.** No depende del ángulo, por construcción: el alfa por corte se deriva de la cantidad de cortes justamente para que la pila converja siempre al mismo valor.

### 2.1 Lo que sí cambia en ángulo rasante es lo contrario

Los ocho cruces del rayo con los ocho cortes ocurren a **ocho posiciones horizontales distintas**, y en ángulo rasante esas posiciones están muy separadas:

```
separación horizontal = espesor de la capa / tan(elevación)
```

Para la capa baja (16 bloques de espesor) con la cámara a 5° de elevación: **183 bloques**. Son once celdas de 16 bloques entre el primer cruce y el último.

En ángulo rasante el compuesto **promedia celdas muy separadas entre sí**. Eso *difumina* la silueta de cada celda individual en vez de endurecerla — el efecto contrario al que la hipótesis necesita.

### 2.2 Y la rasterización tampoco la ayuda

Dentro de una región, las celdas comparten sus aristas **exactamente**. Las coordenadas son locales a la región y múltiplos enteros de 16 o 32, todos exactamente representables en `float`:

```java
float x0 = cx * cellSize;
float x1 = x0 + cellSize;      // x1 de una celda == x0 de la vecina, bit a bit
```

La regla de relleno de OpenGL garantiza que una arista compartida exacta queda cubierta **una sola vez**: no hay doble cobertura ni grieta entre celdas vecinas del mismo nivel de detalle.

La consecuencia es una predicción falsable: un borde de celda puede producir un **escalón** de brillo, nunca una **cresta**. Y lo que se midió fueron crestas (ver §4.1).

---

## 3. Lo que el análisis acierta

### 3.1 La crítica a los tests es correcta y se acepta entera

> *"Las pruebas de `CoreSmokeTest` son matemáticas puras, no dibujan nada... Nunca instancian `RegionMeshBuilder`, nunca arman un `VertexBuffer`, nunca simulan una cámara en ángulo rasante. Es una cobertura muy sólida del algoritmo, pero cero cobertura del resultado visual final."*

Es exactamente así. Lo verifiqué leyendo el test: `seams()` llama a `sliceHeight`, `sliceThreshold`, `sliceT` y `shade` con datos sintéticos y comprueba cuatro propiedades algebraicas —alturas distintas por nivel, subconjunto exacto entre niveles vecinos, unión que no crece, y umbral y sombreado iguales en cortes coplanares—. Ni una sola de ellas toca geometría dibujada.

Es cobertura sólida del algoritmo y cobertura cero del resultado, que es donde vive esta clase de defecto.

### 3.2 El hueco del lado de celda entre niveles es real

Su hipótesis 3.3 señala dos cosas que la escalera de alturas no cubre. La primera —que el lado fino conserva cortes que el grueso no tiene— es cierta pero acotada: la unión de alturas no crece, que es lo que evitaba el +8 % de opacidad.

La segunda es un hallazgo genuino:

> *"El tamaño de celda también cambia entre Medio y Bajo (16 contra 32 bloques)... el lado Bajo evalúa el mismo ruido en una grilla más gruesa, así que puede o no activar una celda donde el lado Medio sí la activa. Es una discontinuidad de **forma** entre dos regiones vecinas."*

Correcto, y no lo cubre ninguna corrección actual. Tampoco hay mezcla entre niveles que suavice la transición: el cambio de malla es completo. Es un hueco que yo dejé abierto.

### 3.3 La interpolación quíntica es un punto válido

`NoiseField.valueNoise` usa el suavizado cúbico clásico:

```java
double sx = fx * fx * (3.0D - 2.0D * fx);      // 3t² − 2t³
```

Deja continuos el valor y la primera derivada, pero **no la segunda**. Es una limitación conocida, y es literalmente la razón por la que Perlin la reemplazó por una quíntica (`6t⁵ − 15t⁴ + 10t³`) en su *Improved Noise* de 2002. El cambio es de una línea y no tiene costo apreciable.

**Y le falta su propio mejor argumento.** El umbral **amplifica** ese pliegue:

```java
alfa = min(1, (densidad − umbral) / 0,22)
```

Dividir por 0,22 multiplica por **4,5** cualquier diferencia de pendiente que traiga la densidad. Un pliegue que en el mapa de densidad es casi imperceptible entra al alfa multiplicado por cuatro y medio. Ese es un argumento mucho más fuerte a favor de la hipótesis que el que el documento usa.

### 3.4 Donde se pasa de la raya

> *"El Método 11 de la tanda anterior ya midió esto sin llamarlo así: encontró filas y columnas puntuales con curvatura de 1,8 a 2,0 veces la media. Eso es exactamente la firma de una discontinuidad de segunda derivada."*

No lo es. Un cociente de 1,8–2,0 entre el máximo y la media, sobre 600 filas y 600 columnas de un campo aleatorio suave, es fluctuación estadística normal. Fue un **resultado nulo**.

En el inventario de métodos quedó anotado sin veredicto porque ese documento se pidió explícitamente sin conclusiones. Leerlo como confirmación es invertir el signo del dato.

---

## 4. Lo que al análisis le falta

### 4.1 El discriminante que ya estaba medido: cresta contra escalón

El Método 9 midió las anomalías con **residuo de mediana móvil**, que por construcción detecta crestas: resta a cada fila su propia mediana local y busca los máximos del residuo. Encontró crestas.

Eso parte las hipótesis en dos familias con predicciones opuestas:

| Forma predicha | Qué significa | Hipótesis que la predicen |
|---|---|---|
| **Cresta** | Opacidad de más en una banda angosta, que vuelve al nivel de base a ambos lados | Costura con planos de más *(ya corregida)*, pliegue del ruido, doble cobertura sub-píxel |
| **Escalón** | El brillo cambia de nivel y se queda en el nuevo | Silueta de celda, cambio de lado de celda entre niveles |

**La hipótesis que el análisis pone primera predice escalón. Lo medido fue cresta.**

### 4.2 Las costuras de LOD son arcos, no un abanico

El documento apoya varias hipótesis en un patrón de líneas convergiendo a un punto:

> *"se ve un patrón muy nítido de líneas convergiendo en un solo punto (forma de 'V' o abanico), que es exactamente la proyección en perspectiva de un conjunto de líneas paralelas en el mundo"*

La primera mitad es correcta: un abanico que converge a un punto de fuga son **líneas paralelas en el mundo**. La consecuencia que no saca es que eso **descarta** una de sus propias hipótesis de categoría alta.

Los tramos de nivel de detalle son **anillos alrededor del jugador**. Sus costuras no son rectas paralelas: son **arcos concéntricos**, que en pantalla cruzan el abanico en vez de formarlo. Si el patrón observado es de verdad un abanico convergente, apunta a una familia alineada con una grilla —celdas, bordes de región o red de ruido— y en contra de las costuras de LOD.

### 4.3 Por qué unas líneas se ven nítidas y otras no

El difuminado de 183 bloques de §2.1 ocurre **a lo largo de la dirección de vista**. De ahí sale una explicación del abanico mejor que la del documento:

- Una línea que corre aproximadamente **paralela** a hacia dónde mirás se difumina **a lo largo de sí misma**, y queda intacta.
- Una línea **perpendicular** se difumina a través de sí misma, y se borra.

Por eso se ven solo las que apuntan al punto de fuga. Y eso le quita al abanico todo valor como evidencia a favor de una hipótesis en particular: el filtro vale igual para bordes de celda, bordes de región y red de ruido.

### 4.4 El separador definitivo: el espaciado

Ninguna de las pruebas propuestas mide lo único que separa las tres familias candidatas de una sola vez. Cada fuente deja líneas a una distancia distinta **en el mundo**:

| Fuente | Separación en bloques |
|---|---|
| Bordes de celda (nivel Alto y Medio) | 16 |
| Bordes de celda (nivel Bajo) | 32 |
| Red de ruido, capa baja (escala 300) | 37,5 · 75 · 150 · 300 |
| Red de ruido, capa media (escala 420) | 52,5 · 105 · 210 · 420 |
| Red de ruido, capa alta (escala 620) | 77,5 · 155 · 310 · 620 |
| Bordes de región | 256 |
| Costuras de nivel de detalle | *no aplica: arcos, no rectas paralelas* |

Con la posición del jugador en pantalla (F3) y dos líneas del fotograma alcanza para descartar casi todo de una.

---

## 5. Ranking comparado

| Hipótesis | El análisis | Esta revisión | Por qué cambia |
|---|---|---|---|
| Silueta dura de celda en ángulo rasante | **1ª — Alta** | **Baja** | El mecanismo no existe en planos de espesor cero; las aristas compartidas son exactas y no dejan grieta; predice escalón y lo medido fue cresta |
| Pliegue del ruido (cúbica en vez de quíntica) | 2ª — Alta | **1ª**, si el bug persiste | Real, amplificado ×4,5 por el umbral, y se corrige con una línea. La "confirmación" que cita es un resultado nulo |
| Costura de LOD abierta (celda 16→32) | 3ª — Alta | **2ª** | Hueco real y no cubierto. Pero deja arcos, no el abanico que el propio documento describe |
| Precisión sub-píxel entre regiones | Media | **3ª** | Puede dar cresta o surco según hacia dónde redondee. Espaciado de 256 bloques, fácil de verificar |
| Sin orden de profundidad dentro de la región | Media | De acuerdo | Real, pero produce una diferencia uniforme de brillo, no una línea |
| Banding de color de 8 bits | Media | De acuerdo | El propio documento lo baja bien: la firma esperada son bandas siguiendo el degradé del cielo |
| Malla vieja durante una transición de LOD | Media | De acuerdo | Solo relevante combinado con otra causa |
| Categoría 1 completa | Baja | De acuerdo | Correctamente descartados |

Una nota sobre la hipótesis de los "cuadrados que se recargan": el documento la vincula a la silueta de celda vista de frente. Ese vínculo se cae junto con el mecanismo de §2, pero la observación de fondo —que conviene investigar los dos síntomas juntos— sigue siendo razonable.

---

## 6. Qué hacer, en orden

**Primero: probar la 0.2.4.** Mismo ángulo rasante del video. Es una tarde de juego y decide si hay algo que investigar. Si las líneas desaparecieron, todo lo anterior describe causas hipotéticas de un defecto que ya no existe.

**Si siguen: medir el espaciado antes de tocar código.** Con F3 y dos líneas del fotograma, contra la tabla de §4.4. Es el único dato que separa las familias, y no aparece entre las pruebas propuestas.

**En cualquiera de los dos casos, dos cosas valen igual:**

1. **Cambiar la interpolación del ruido a quíntica.** Una línea, sin costo medible, elimina una clase entera de artefacto — se esté manifestando ahora o no.
2. **Cerrar el hueco del lado de celda entre Medio y Bajo.** Es real y quedó abierto.

---

*Revisión hecha sobre el código de la 0.2.4 en el commit `9effa94`. Las afirmaciones sobre mediciones previas están verificadas contra `docs/lineas-metodos-aplicados.md`; las de código, contra el árbol del repositorio; las de cronología, contra el historial de commits.*
