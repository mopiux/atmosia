# Revisión del análisis externo sobre las líneas rectas

*Respuesta punto por punto a `analisis-causas-lineas-rectas.md`, un diagnóstico hecho con Claude Sonnet sobre el código de la 0.2.4 y sobre una grabación posterior al arreglo de la escalera de alturas.*

*Este documento no propone cambios ni toca código. Verifica las afirmaciones del análisis contra el código real y contra las mediciones existentes, y reordena las hipótesis según lo que esa verificación deja en pie.*

> **Nota sobre la versión anterior de este documento.** La primera versión sostenía que el video analizado era anterior al arreglo de la escalera y que, por lo tanto, la premisa del análisis no estaba verificada. **Era falso.** La prueba de la 0.2.4 se hizo antes del análisis y con una grabación nueva (ver §1). Esa corrección cambia el veredicto y cambia el orden de las hipótesis.

---

## Veredicto en una página

El análisis es sólido. Su inferencia central —que si el bug sobrevive al arreglo de la escalera tiene que haber más de un mecanismo produciendo la misma firma visual— es correcta y está bien fundada. Sus tres hipótesis de categoría alta apuntan a lugares reales del código, y su crítica a la cobertura de las pruebas da en el blanco.

Queda una objeción técnica concreta y tres cosas para agregar.

| | |
|---|---|
| **La premisa** | **Se sostiene.** Verificada: el video del análisis es una grabación distinta y posterior al arreglo. |
| **La objeción** | El mecanismo que da su hipótesis principal —opacidad que se integra a lo largo del recorrido del rayo dentro de la celda— no puede existir en planos de espesor cero. Pero **su conclusión sobrevive con otro mecanismo**, que además la explica mejor (§3). |
| **Lo que acierta** | La crítica a los tests. El hueco del lado de celda 16→32 entre niveles. El cambio a interpolación quíntica. |
| **Lo que le falta** | Una prueba de tres vías que separa las familias de causas sin tocar código ni hacer cuentas, y que ninguna de sus pruebas propuestas cubre (§5.1). |

---

## 1. La premisa: verificada

El análisis se apoya en esta frase, que es la bisagra de todo el resto:

> *"Si el síntoma persiste en 0.2.4 con esa causa ya cerrada, lo más lógico es que exista más de un mecanismo generando el mismo tipo de banda."*

**El síntoma persiste en la 0.2.4.** La prueba se hizo antes del análisis, y la grabación que el análisis usa es de esa prueba, no del material anterior.

La comprobación es directa: el video del material previo y el que el análisis describe en su encabezado no son el mismo archivo.

| | Grabación anterior | Grabación del análisis |
|---|---|---|
| Duración | 14,72 s | **17,94 s** |
| Resolución | 1918 × 1012 | 1918 × **1010** |
| Cuadros por segundo | 30 | 30 |

Duraciones distintas y alturas de imagen distintas: son dos capturas separadas, y la del análisis es posterior al commit `ff99077`.

La consecuencia es que **la escalera de alturas cerró una causa y no cerró el defecto**, y que la inferencia de causas múltiples está bien planteada.

### 1.1 Lo que no se hereda de la tanda anterior

Con la premisa en pie hay que ser cuidadoso con lo contrario: **las mediciones de píxeles de la tanda anterior no se transfieren.**

El Método 9 midió, sobre la grabación vieja, residuos de 3 a 8 niveles de gris con anchos de 4 a 6 píxeles. Esa medición describe un cielo que contenía una causa que hoy ya no está —la costura con planos de más, que aportaba un +8 % de opacidad en una banda fina—. No se puede usar para caracterizar lo que queda.

**Hay que volver a medir sobre la grabación nueva**, y hasta que eso pase, cualquier afirmación sobre la *forma* del defecto actual (cresta contra escalón, ancho, contraste) es una conjetura. Esto vale tanto para el análisis externo como para este documento.

---

## 2. La objeción: el mecanismo de la hipótesis 3.1

Es la hipótesis que el análisis pone primera, y el mecanismo que describe no existe en este renderer.

> *"El 'largo óptico' que recorre el rayo de vista dentro de la celda antes de salir de su rectángulo crece muchísimo respecto de mirarla de frente. Eso significa que, aunque el alfa por corte sea bajo, la opacidad acumulada a lo largo de ese tramo del rayo puede ser mucho más alta..."*

Eso es cierto en **ray marching sobre un volumen**, donde la opacidad se integra a lo largo del recorrido y el ángulo de entrada determina cuánto medio atraviesa el rayo.

Acá los cortes son **planos horizontales de espesor cero**. Un rayo cruza un plano exactamente una vez, venga del ángulo que venga, y la mezcla aporta exactamente el alfa de la celda que cruzó. No hay recorrido interno que integrar.

**La opacidad acumulada de la pila es 0,92 mirando derecho hacia arriba y 0,92 mirando al ras.** No depende del ángulo por construcción: el alfa por corte se deriva de la cantidad de cortes justamente para que la pila converja siempre al mismo valor.

Lo que sí cambia en ángulo rasante es otra cosa, y va en la dirección opuesta. Los ocho cruces del rayo con los ocho cortes ocurren a ocho posiciones horizontales distintas, muy separadas entre sí:

```
separación horizontal = espesor de la capa / tan(elevación)
```

Para la capa baja (16 bloques de espesor) a 5° de elevación: **183 bloques**. Once celdas de 16 bloques entre el primer cruce y el último. El compuesto promedia celdas muy separadas, lo cual *difumina* la silueta de cada celda individual en vez de endurecerla.

---

## 3. Pero la conclusión de 3.1 sobrevive, con otro mecanismo

Descartar el mecanismo no descarta la hipótesis, y en este caso hay un mecanismo distinto que llega a la misma conclusión y además explica mejor lo observado.

**Aliasing de una grilla regular en incidencia rasante.**

Los ingredientes están todos presentes:

- La grilla de celdas es **perfectamente regular**: 16 bloques, sin variación.
- Cada borde de celda es una **discontinuidad dura de alfa**: el alfa es constante en toda la superficie del cuadrilátero y salta al valor de la vecina en la arista. No hay degradado.
- **No hay textura, así que no hay mipmapping**: nada filtra el detalle que cae por debajo del tamaño del píxel.
- En ángulo rasante, una sola fila de píxeles de la pantalla abarca **decenas o cientos de bloques** en profundidad, o sea muchas celdas por píxel.
- Y hay **ocho grillas superpuestas**, una por corte, desplazadas en profundidad unas respecto de otras.

Una grilla regular muestreada por otra grilla regular a través de una transformación proyectiva produce **moiré**: familias de bandas que no están en el mundo, sino que nacen del batido entre las dos frecuencias.

Ese mecanismo predice, sin forzar nada:

| Lo observado | Lo que predice el moiré |
|---|---|
| Líneas convergiendo a un punto de fuga | Sí: el batido de una grilla horizontal se alinea con sus direcciones principales |
| No limitado a una costura de 256 bloques | Sí: aparece en cualquier parte del cielo con incidencia suficiente |
| Contrastes distintos entre líneas vecinas | Sí: es característico del batido |
| Aparece en ángulo rasante y no mirando hacia arriba | Sí: es el régimen donde hay muchas celdas por píxel |

O sea: **la hipótesis del análisis estaba bien elegida y mal explicada.** En la primera versión de este documento la bajé a probabilidad baja apoyándome en el error del mecanismo, y eso fue un error mío: la conclusión es independiente del argumento que la sostenía.

### 3.1 Lo que cambia es el arreglo

La diferencia práctica entre los dos mecanismos está en qué corrección corresponde.

Si fuera acumulación óptica, habría que tocar la opacidad. Si es aliasing de una discontinuidad dura, la corrección es **eliminar la discontinuidad**: que el alfa varíe dentro del cuadrilátero en vez de ser constante, interpolado entre los cuatro vértices a partir de las densidades de las celdas vecinas.

Eso convierte la silueta de la nube en algo continuo, sin aristas de alfa, y le quita al aliasing el borde duro del que se alimenta. Tiene un costo: hay que tener la densidad en las **esquinas** de la celda y no solo en su centro —promediando las cuatro celdas vecinas, o muestreando el ruido en los vértices—, lo cual es barato pero no gratis.

---

## 4. Lo que el análisis acierta

### 4.1 La crítica a los tests, entera

> *"Las pruebas de `CoreSmokeTest` son matemáticas puras, no dibujan nada... Nunca instancian `RegionMeshBuilder`, nunca arman un `VertexBuffer`, nunca simulan una cámara en ángulo rasante."*

Exacto. `seams()` llama a `sliceHeight`, `sliceThreshold`, `sliceT` y `shade` con datos sintéticos y comprueba cuatro propiedades algebraicas: alturas distintas por nivel, subconjunto exacto entre niveles vecinos, unión que no crece, y umbral y sombreado iguales en cortes coplanares. Ninguna toca geometría dibujada.

Cobertura sólida del algoritmo y cobertura cero del resultado, que es donde vive esta clase de defecto. Y el defecto que sobrevivió al arreglo es la demostración: los tests pasan en verde sobre un cielo que sigue teniendo líneas.

### 4.2 El hueco del lado de celda entre niveles es real

> *"El tamaño de celda también cambia entre Medio y Bajo (16 contra 32 bloques)... el lado Bajo evalúa el mismo ruido en una grilla más gruesa, así que puede o no activar una celda donde el lado Medio sí la activa. Es una discontinuidad de **forma** entre dos regiones vecinas."*

Correcto y no cubierto por ninguna corrección actual. Tampoco hay mezcla entre niveles que suavice la transición: el cambio de malla es completo. Es un hueco que quedó abierto de mi lado.

### 4.3 La interpolación quíntica es un punto válido

`NoiseField.valueNoise` usa el suavizado cúbico clásico:

```java
double sx = fx * fx * (3.0D - 2.0D * fx);      // 3t² − 2t³
```

Deja continuos el valor y la primera derivada, pero **no la segunda**. Es la razón por la que Perlin la reemplazó por una quíntica (`6t⁵ − 15t⁴ + 10t³`) en su *Improved Noise* de 2002. El cambio es de una línea y no tiene costo apreciable.

**Y le falta su propio mejor argumento.** El umbral amplifica ese pliegue:

```java
alfa = min(1, (densidad − umbral) / 0,22)
```

Dividir por 0,22 multiplica por **4,5** cualquier diferencia de pendiente que traiga la densidad. Un pliegue casi imperceptible en el mapa de densidad entra al alfa multiplicado por cuatro y medio.

### 4.4 Donde sí se pasa de la raya

> *"El Método 11 ya midió esto sin llamarlo así: encontró curvatura de 1,8 a 2,0 veces la media. Eso es exactamente la firma de una discontinuidad de segunda derivada."*

No lo es. Un cociente de 1,8–2,0 entre el máximo y la media, sobre 600 filas y 600 columnas de un campo aleatorio suave, es fluctuación estadística normal. Fue un resultado nulo, anotado sin veredicto en el inventario porque ese documento se pidió sin conclusiones. Leerlo como confirmación invierte el signo del dato.

La hipótesis de la quíntica es buena igual. Simplemente no tiene esa evidencia a favor.

---

## 5. Lo que le falta

### 5.1 La prueba de tres vías

Ninguna de las pruebas propuestas distingue las familias de causas, y hay una que lo hace de una sola vez, sin F3, sin cuentas y sin tocar código.

**Cada familia se comporta distinto frente a dos movimientos independientes: girar la cámara sin moverse, y quedarse quieto dejando correr el viento.**

| Familia | Al girar solo la cámara | Al quedarse quieto (viento corriendo) |
|---|---|---|
| **Aliasing / moiré** (grilla de celdas) | Las líneas **nadan**: cambian de lugar, de separación, aparecen y desaparecen | Cambian, pero no acompañando a las nubes |
| **Red de ruido** o **bordes de región** | Quedan **ancladas al cielo**: la línea sigue sobre la misma formación | **Derivan junto con las nubes**: viven en espacio de nube |
| **Costuras de nivel de detalle** | Quedan ancladas **al jugador**, no al cielo: son anillos centrados en vos | **No derivan**: se quedan a la misma distancia tuya mientras las nubes pasan por debajo |

Tres comportamientos distintos y mutuamente excluyentes, observables a ojo en menos de un minuto de juego.

### 5.2 Las costuras de nivel de detalle son arcos, no un abanico

El análisis apoya varias hipótesis en el patrón de líneas convergiendo a un punto:

> *"se ve un patrón muy nítido de líneas convergiendo en un solo punto (forma de 'V' o abanico), que es exactamente la proyección en perspectiva de un conjunto de líneas paralelas en el mundo"*

La primera mitad es correcta. La consecuencia que no saca es que eso pesa **en contra** de una de sus propias hipótesis de categoría alta: los tramos de nivel de detalle son **anillos alrededor del jugador**, así que sus costuras no son rectas paralelas sino **arcos concéntricos**, que en pantalla cruzan el abanico en vez de formarlo.

### 5.3 Por qué unas líneas se ven y otras no

El difuminado de 183 bloques de §2 ocurre **a lo largo de la dirección de vista**:

- Una línea aproximadamente **paralela** a hacia dónde mirás se difumina a lo largo de sí misma, y queda intacta.
- Una línea **perpendicular** se difumina a través de sí misma, y se borra.

Por eso sobreviven solo las que apuntan al punto de fuga. Y eso le quita al abanico valor como evidencia a favor de una hipótesis en particular: el filtro vale igual para bordes de celda, bordes de región y red de ruido.

### 5.4 El espaciado, como prueba de respaldo

Si la prueba de §5.1 apunta a una causa anclada al mundo, el espaciado termina de identificarla. Cada fuente deja líneas a una distancia propia:

| Fuente | Separación en bloques |
|---|---|
| Bordes de celda (nivel Alto y Medio) | 16 |
| Bordes de celda (nivel Bajo) | 32 |
| Red de ruido, capa baja (escala 300) | 37,5 · 75 · 150 · 300 |
| Red de ruido, capa media (escala 420) | 52,5 · 105 · 210 · 420 |
| Red de ruido, capa alta (escala 620) | 77,5 · 155 · 310 · 620 |
| Bordes de región | 256 |

Con una salvedad que importa: **si la causa es el aliasing de §3, el espaciado en pantalla no va a corresponder a ninguna de estas cifras**, porque las bandas de moiré son un batido y no están en el mundo. Que las cuentas no cierren con ninguna fila de la tabla es, en sí mismo, un resultado informativo.

---

## 6. Ranking comparado

| Hipótesis | El análisis | Esta revisión | Por qué |
|---|---|---|---|
| Silueta dura de celda en ángulo rasante | **1ª — Alta** | **1ª** | Se mantiene arriba, pero por aliasing de una grilla regular sin filtrado (§3), no por acumulación óptica. Cambia el arreglo que corresponde |
| Costura de nivel de detalle abierta (celda 16→32) | 3ª — Alta | **2ª** | Hueco real y no cubierto. Deja arcos, no abanico, lo cual la prueba de §5.1 distingue de inmediato |
| Pliegue del ruido (cúbica en vez de quíntica) | 2ª — Alta | **3ª** | Real y amplificado ×4,5 por el umbral, pero la "confirmación" que cita es un resultado nulo. Se corrige con una línea, así que conviene hacerlo igual |
| Precisión sub-píxel entre regiones | Media | De acuerdo | Solo puede producir líneas cada 256 bloques; la prueba de §5.1 la agrupa con la red de ruido y el espaciado la separa |
| Sin orden de profundidad dentro de la región | Media | De acuerdo | Real, pero produce una diferencia uniforme de brillo, no una línea |
| Banding de color de 8 bits | Media | De acuerdo | El propio análisis lo baja bien |
| Malla vieja durante una transición de nivel | Media | De acuerdo | Solo relevante combinado con otra causa |
| Categoría 1 completa | Baja | De acuerdo | Correctamente descartados |

Sobre el segundo síntoma (los cuadrados que se recargan): el análisis lo vincula a la misma causa vista desde otro ángulo. Con el mecanismo corregido de §3 el vínculo se mantiene e incluso se refuerza — un borde duro de alfa se ve como línea de canto y como cuadrado de frente, y en los dos casos el origen es la discontinuidad, no el ángulo.

---

## 7. Qué hacer, en orden

1. **La prueba de tres vías de §5.1.** Un minuto de juego, sin herramientas. Separa aliasing de causa anclada al mundo de costura de nivel de detalle, que es la bifurcación que decide todo lo demás.

2. **Volver a medir los fotogramas de la grabación nueva** (§1.1). Forma del perfil, ancho, contraste y separación en pantalla. Lo de la tanda anterior describe un cielo que ya no existe.

3. **Según lo que salga de 1 y 2**, atacar una sola causa por vez y volver a grabar en el mismo ángulo entre una y otra. Tres correcciones juntas sobre un defecto con causa desconocida no se pueden atribuir después.

**Independientemente del resultado**, dos cosas valen igual y son baratas:

- **Cambiar la interpolación del ruido a quíntica.** Una línea, sin costo medible, elimina una clase entera de artefacto.
- **Cerrar el hueco del lado de celda entre Medio y Bajo.**

Y una tercera que vale la pena tener presente aunque no se haga ahora: **los tests no cubren nada de lo que está fallando.** Mientras la verificación siga siendo algebraica, cualquier corrección de este defecto se va a validar mirando el cielo, que es exactamente lo que ya pasó dos veces.

---

*Revisión hecha sobre el código de la 0.2.4. Las afirmaciones sobre mediciones previas están verificadas contra `docs/lineas-metodos-aplicados.md`; las de código, contra el árbol del repositorio; la cronología de las grabaciones, contra los metadatos de los archivos de video.*
