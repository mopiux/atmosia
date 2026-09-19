# Lineas rectas en las nubes: metodos aplicados

*Registro de todo lo que se hizo para atacar el artefacto de las lineas rectas que aparecen en el cielo. Es un inventario de operaciones: que se cambio, con que valores, en que archivo, y con que herramientas se midio.*

*Cubre desde la version 0.0.1 hasta la 0.2.4.*

---

## Indice de metodos

| # | Metodo | Tipo | Version |
|---|---|---|---|
| 1 | Elevar el piso del domo de nubes | Cambio de parametro | 0.0.1 |
| 2 | Subir el multiplicador de distancia por defecto | Cambio de parametro | 0.0.1 |
| 3 | Acotar el tamano maximo de celda | Cambio de estructura | 0.0.1 |
| 4 | Bajar la cobertura de las tres capas | Cambio de parametro | 0.0.1 |
| 5 | Derivar el alfa por corte de la cantidad de cortes | Cambio de formula | 0.2.2 |
| 6 | Aclarar el sombreado de la base | Cambio de parametro | 0.2.2 |
| 7 | Eliminar el cuarto nivel de detalle | Cambio de estructura | 0.2.2 |
| 8 | Prefetch direccional de regiones | Cambio de comportamiento | 0.2.2 |
| 9 | Extraccion y medicion de fotogramas | Instrumentacion | 0.2.4 |
| 10 | Volcado del campo de densidad a imagen | Instrumentacion | 0.2.4 |
| 11 | Analisis de curvatura por fila y columna | Instrumentacion | 0.2.4 |
| 12 | Inspeccion de la funcion de hash | Revision de codigo | 0.2.4 |
| 13 | Inspeccion de la emision de cuadrilateros | Revision de codigo | 0.2.4 |
| 14 | Inspeccion de la grilla de muestreo entre regiones | Revision de codigo | 0.2.4 |
| 15 | Sonda de alturas de corte por nivel | Instrumentacion | 0.2.4 |
| 16 | Escalera de alturas compartida entre niveles | Cambio de formula | 0.2.4 |
| 17 | Umbral en funcion de la altura normalizada | Cambio de formula | 0.2.4 |
| 18 | Sombreado en funcion de la altura normalizada | Cambio de formula | 0.2.4 |
| 19 | Comprobaciones automaticas de costura | Pruebas | 0.2.4 |

---

## Primera tanda (0.0.1)

Punto de partida: ocho capturas de pantalla del juego, tomadas desde el piso, desde lejos, volando sobre las nubes y en varios planos cercanos.

### Metodo 1 - Elevar el piso del domo de nubes

En `LodSelector.forRenderDistance` se modifico el calculo de la distancia maxima a la que existen regiones de nube. La expresion pasa de usar directamente el render distance del jugador por un multiplicador, a aplicar un piso minimo:

```java
double max = Math.max(512.0D, worldDistance * multiplier);
```

El piso de 512 bloques hace que, con cualquier render distance, el limite exterior del domo quede a esa distancia como minimo.

### Metodo 2 - Subir el multiplicador de distancia por defecto

En `AtmosiaConfig`, el parametro `distanceMultiplier` paso de **1,5 a 3,0**. Es el factor que multiplica el render distance del jugador para obtener el alcance del domo. Tambien se subio `maxCachedRegions` de 192 a 384 para sostener el alcance mayor.

### Metodo 3 - Acotar el tamano maximo de celda

En el enumerado `LodLevel` se redujo el lado de celda de los niveles lejanos. La tabla paso de:

| Nivel | Cortes | Celda (antes) | Celda (despues) |
|---|---|---|---|
| HIGH | 8 | 16 | 16 |
| MEDIUM | 4 | 32 | 16 |
| LOW | 2 | 64 | 32 |
| MINIMAL | 1 | 128 | 64 |

La celda es el lado, en bloques, de cada cuadrilatero que se emite. Se dejo escrito en el propio enumerado el limite adoptado, para que el valor no vuelva a subirse.

### Metodo 4 - Bajar la cobertura de las tres capas

En `CloudLayerDef` se redujo el parametro `coverage` de cada capa:

| Capa | Antes | Despues |
|---|---|---|
| LOW | 0,52 | 0,42 |
| MID | 0,46 | 0,36 |
| HIGH | 0,34 | 0,26 |

La cobertura desplaza el umbral de densidad a partir del cual una celda emite geometria.

---

## Segunda tanda (0.2.2)

Punto de partida: capturas tomadas volando con elytra por debajo de la capa de nubes, y un documento de diagnostico externo.

### Metodo 5 - Derivar el alfa por corte de la cantidad de cortes

En `RegionMeshBuilder` existian dos constantes fijas: `SLICE_ALPHA_STACKED = 0,55` para niveles con varios cortes y `SLICE_ALPHA_SINGLE = 0,85` para el de un solo corte. Las dos se eliminaron.

En su lugar se agrego a `DensityField` una funcion que calcula el alfa de cada corte a partir de cuantos cortes tiene el nivel, de modo que la pila completa llegue siempre a la misma opacidad:

```java
public static float sliceAlpha(int slices) {
    int n = Math.max(1, slices);
    return (float) (1.0D - Math.pow(1.0D - STACK_OPACITY, 1.0D / n));
}
```

Con `STACK_OPACITY = 0,92`, los valores resultantes por nivel son:

| Nivel | Cortes | Alfa por corte |
|---|---|---|
| HIGH | 8 | 0,2707 |
| MEDIUM | 4 | 0,4682 |
| LOW | 2 | 0,7172 |

### Metodo 6 - Aclarar el sombreado de la base

En `DensityField`, la constante `BOTTOM_SHADE` paso de **0,62 a 0,78**. Es el multiplicador de color que recibe el corte mas bajo de una capa; el mas alto recibe 1,0 y los intermedios se interpolan entre los dos.

### Metodo 7 - Eliminar el cuarto nivel de detalle

Se quito la constante `MINIMAL` del enumerado `LodLevel`, que definia un nivel de un corte y celdas de 64 bloques. En `LodSelector` se elimino el campo `lowUntil` y la rama que devolvia ese nivel, quedando tres tramos de distancia en lugar de cuatro.

### Metodo 8 - Prefetch direccional de regiones

Se agrego la clase `MotionPrefetch` al nucleo. Estima la velocidad de la camara con suavizado exponencial sobre las posiciones de cada frame y proyecta una posicion futura a un horizonte de 1,5 segundos.

En `CloudRenderer`, el barrido de regiones paso a usar dos posiciones distintas:

- la posicion real de la camara decide que regiones se dibujan, con que nivel de detalle se muestran y con que atenuacion;
- la posicion proyectada decide que regiones se encolan para generar, con que nivel se construyen y en que orden de prioridad.

El radio del barrido se amplio sumando la longitud del adelanto al alcance del domo. Se agregaron tres cortes: por debajo de 8 bloques por segundo el adelanto es cero, por encima de 200 bloques por segundo la muestra de velocidad se descarta, y el adelanto se acota a 256 bloques.

---

## Tercera tanda (0.2.4)

Punto de partida: un video de 14,7 segundos a 30 fps y 1918x1012, grabado volando por debajo de la capa.

### Metodo 9 - Extraccion y medicion de fotogramas

Se instalo un binario de ffmpeg mediante el paquete `imageio-ffmpeg` y se extrajeron fotogramas del video de dos formas: uno cada tres segundos, y tres fotogramas puntuales por numero de cuadro (90, 200 y 330).

Sobre uno de los fotogramas se hicieron dos mediciones con Pillow y NumPy:

**Deteccion de crestas por residuo de mediana movil.** Para cuatro filas de pixeles (y = 150, 300, 420 y 520) se calculo una mediana movil de ventana 31 y se resto de la fila original. Los maximos del residuo dan la posicion horizontal y la amplitud de cada anomalia:

```python
k = 31
pad = np.pad(fila, k//2, mode='edge')
med = np.array([np.median(pad[i:i+k]) for i in range(len(fila))])
resid = fila - med
```

Los residuos medidos fueron de entre 3 y 8 niveles de gris, con anchos de 4 a 6 pixeles.

**Perfil transversal.** Se volco el valor de gris pixel a pixel a lo largo de un tramo de 60 columnas que atraviesa una de las anomalias, con una barra de texto proporcional al valor, para ver la forma del perfil.

### Metodo 10 - Volcado del campo de densidad a imagen

Se escribio una clase Java auxiliar (`Dump.java`) que carga el nucleo real del mod, evalua `NoiseField.fbm` sobre una grilla de 600 x 600 puntos y escribe el resultado como imagen PGM en escala de grises, normalizada entre el minimo y el maximo del recorte.

Se ejecuto dos veces sobre una ventana de 2.400 bloques de lado:

- centrada en el origen del espacio de nube;
- centrada a 500.000 bloques del origen.

### Metodo 11 - Analisis de curvatura por fila y columna

Dentro de la misma clase auxiliar se calculo, para cada fila y cada columna de la grilla, la suma de la segunda diferencia absoluta:

```java
s += Math.abs(v[j+1][i] - 2*v[j][i] + v[j-1][i]);
```

Se reporto la fila y la columna con mayor valor, su coordenada en bloques, y el cociente contra la media de todas las filas y columnas. Los cocientes medidos fueron de 1,8 a 2,0.

### Metodo 12 - Inspeccion de la funcion de hash

Se leyo la funcion `hashToUnit` de `NoiseField`, que combina la coordenada entera de celda en X, la de Z y el numero de octava mediante multiplicaciones por constantes de 64 bits y XOR sucesivos, seguidos de una mezcla estilo splitmix64. Se reviso en particular el comportamiento de los terminos cuando alguna coordenada vale cero.

### Metodo 13 - Inspeccion de la emision de cuadrilateros

Se leyo el bucle de emision de `RegionMeshBuilder` para comprobar las coordenadas de cada cuadrilatero:

```java
float x0 = cx * cellSize;
float z0 = cz * cellSize;
float x1 = x0 + cellSize;
float z1 = z0 + cellSize;
```

Se verifico la relacion entre el ultimo cuadrilatero de una region y el primero de la region vecina, y el orden de los cuatro vertices.

### Metodo 14 - Inspeccion de la grilla de muestreo entre regiones

Se leyo `DensityJob.compute` para comprobar en que punto de cada celda se evalua la densidad:

```java
double x = originX + (cx + 0.5D) * cellSize;
```

Se calcularon las coordenadas absolutas de la ultima muestra de una region y de la primera de la siguiente, y se comparo la separacion entre ellas contra la separacion entre muestras internas.

### Metodo 15 - Sonda de alturas de corte por nivel

Se escribio una segunda clase Java auxiliar (`Seam.java`) que, cargando el nucleo real, calcula e imprime:

- la lista completa de alturas de corte de cada nivel de detalle para la capa baja, mediante `DensityField.sliceHeight`;
- cuantas alturas de cada nivel grueso coinciden con alguna del nivel fino inmediatamente anterior, con tolerancia de 1e-9;
- el tamano de la union de los dos conjuntos de alturas para cada par de niveles vecinos;
- el alfa acumulado de cada lado y el de la union, con la formula `1 - producto(1 - alfa_i)`.

### Metodo 16 - Escalera de alturas compartida entre niveles

En `DensityField` se sustituyo el calculo de la posicion vertical de cada corte. La formula anterior repartia los cortes dentro del espesor de la capa segun la cantidad de cortes del nivel:

```java
double t = (index + 0.5D) / slices;
```

La nueva formula ubica los cortes sobre peldanos de una escalera fija de ocho posiciones, compartida por todos los niveles, y cada nivel toma un peldano de cada `8 / cortes`:

```java
public static double sliceT(int index, int slices) {
    if (slices <= 1) {
        return 0.5D;
    }
    int step = Math.max(1, LADDER_STEPS / slices);
    int rung = index * step + (step == 1 ? 0 : 1);
    rung = Math.min(rung, LADDER_STEPS - 1);
    return (rung * 2 + 1) / (double) (LADDER_STEPS * 2);
}
```

El desplazamiento impar cuando el paso es mayor que uno se eligio para que los peldanos de cuatro cortes queden en los indices 1, 3, 5 y 7, y los de dos cortes en los indices 1 y 5.

### Metodo 17 - Umbral en funcion de la altura normalizada

El umbral de densidad de cada corte se calculaba a partir del indice del corte y de la cantidad total de cortes. Se separo en dos funciones: una que recibe la altura normalizada, y la anterior que ahora delega en ella pasandole la posicion de la escalera:

```java
public double thresholdAt(double t) {
    double fromCenter = Math.abs(t - 0.5D) * 2.0D;
    return 0.06D + 0.62D * Math.pow(fromCenter, 1.6D);
}

public double sliceThreshold(int index, int slices) {
    if (slices <= 1) {
        return 0.18D;
    }
    return this.thresholdAt(sliceT(index, slices));
}
```

### Metodo 18 - Sombreado en funcion de la altura normalizada

El multiplicador de color por altura se calculaba interpolando entre `BOTTOM_SHADE` y 1,0 segun el indice del corte dividido por la cantidad de cortes menos uno. Paso a interpolarse segun la altura normalizada de la escalera:

```java
float vertical = slices <= 1
        ? 0.88F
        : BOTTOM_SHADE + (1.0F - BOTTOM_SHADE) * (float) sliceT(sliceIndex, slices);
```

### Metodo 19 - Comprobaciones automaticas de costura

Se agrego al conjunto de pruebas del nucleo un bloque de comprobaciones que se ejecuta con `javac` y `java`, sin Minecraft. Verifica, para cada par de niveles vecinos:

- que cada nivel produzca tantas alturas distintas como cortes declara;
- que el conjunto de alturas del nivel grueso este contenido en el del nivel fino;
- que la union de los dos conjuntos tenga el mismo tamano que el del nivel fino;
- que dos cortes de niveles distintos que caen a la misma altura devuelvan el mismo umbral;
- que esos mismos cortes devuelvan el mismo sombreado;
- que los cortes de cada nivel sigan repartidos por el espesor de la capa y no amontonados en un extremo.

---

## Herramientas usadas

| Herramienta | Uso |
|---|---|
| ffmpeg (via `imageio-ffmpeg`) | Extraccion de fotogramas del video, conversion PGM a PNG |
| Pillow | Lectura de fotogramas a escala de grises |
| NumPy | Mediana movil, residuos, perfiles de pixel |
| `javac` / `java` | Ejecucion de las clases auxiliares y de las pruebas del nucleo, sin Minecraft |
| Clases auxiliares `Dump.java` y `Seam.java` | Volcado del campo de densidad y sonda de alturas de corte |

---

## Archivos tocados

| Archivo | Metodos |
|---|---|
| `core/LodSelector.java` | 1, 7 |
| `core/LodLevel.java` | 3, 7 |
| `core/CloudLayerDef.java` | 4 |
| `core/DensityField.java` | 5, 6, 16, 17, 18 |
| `core/MotionPrefetch.java` | 8 |
| `client/RegionMeshBuilder.java` | 5, 13 |
| `client/CloudRenderer.java` | 8 |
| `client/DensityJob.java` | 14 |
| `core/NoiseField.java` | 12 |
| `AtmosiaConfig.java` | 2 |
| `src/test/.../CoreSmokeTest.java` | 19 |
