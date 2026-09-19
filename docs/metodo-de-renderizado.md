# El método: cómo se cargan y se dibujan las nubes

*Descripción técnica del sistema tal como está en la versión 0.2.6. Solo el método: qué hace cada pieza, en qué orden y con qué números.*

---

## 1. La idea en una frase

Atmosia no guarda nubes: guarda una **función**. El cielo entero es el resultado de evaluar una función determinista sobre coordenadas, y lo único que se conserva en memoria es la geometría de los pedazos que el jugador tiene cerca en este momento.

De ahí salen las tres propiedades que gobiernan todo el resto:

- **Nada se genera dos veces igual y distinto.** La misma seed y las mismas coordenadas dan siempre el mismo valor, así que una zona del cielo se puede tirar y recalcular cuando haga falta sin que cambie de forma.
- **Nada se guarda en disco.** No hay archivos de nubes, no hay estado que sincronizar, no hay nada que migrar entre versiones.
- **El costo se acota en el frame, no en el total.** No existe "generar todo lo que hace falta": existe "generar lo que entra en el presupuesto de este frame".

---

## 2. Vocabulario

Cinco palabras que se usan en todo el documento con un sentido preciso.

| Palabra | Qué es |
|---|---|
| **Capa** (*layer*) | Un estrato horizontal de nubes con su propia altura, color, velocidad y escala. Hay tres. |
| **Región** (*region*) | Un cuadrado de **256 × 256 bloques** de una capa. Es la unidad que se genera, se cachea y se dibuja. |
| **Celda** (*cell*) | La subdivisión de una región donde se evalúa la densidad. Mide 16 o 32 bloques según el nivel de detalle. |
| **Corte** (*slice*) | Un plano horizontal translúcido dentro de una capa. Apilar varios es lo que produce el volumen. |
| **Espacio de nube** | Sistema de coordenadas con el viento ya descontado. Es donde viven las regiones. |

---

## 3. El recorrido de un frame

Una sola llamada por frame, desde el evento de render del nivel. Lo que hace, en orden:

```
 1. Avanzar el contador de frames
 2. Releer la configuración y reaccionar a lo que cambió
 3. Reiniciar el presupuesto del frame
 4. Calcular el tiempo del mundo -> desplazamiento del viento
 5. Actualizar la velocidad estimada de la cámara
 6. Construir la escala de LOD a partir del render distance
 7. Barrer las regiones candidatas: dibujar las que están, encolar las que faltan
 8. Construir mallas con los resultados listos, hasta agotar el presupuesto
 9. Dibujar, de lejos a cerca
10. Desalojar de la caché lo más viejo si se pasó del tamaño máximo
```

El trabajo está repartido entre dos clases de hilo, y el reparto no es arbitrario: todo lo que toca OpenGL vive en el hilo de render porque ahí está el contexto gráfico; lo demás se puede mover.

| Etapa | Hilo | Por qué ahí |
|---|---|---|
| Decidir qué regiones hacen falta | Render | Necesita la cámara y el frustum del frame |
| **Calcular el campo de densidad** | **Trabajadores** | Es la parte cara y no toca nada del juego |
| Construir la malla y subirla a la GPU | Render | `BufferBuilder` y `VertexBuffer` son OpenGL |
| Dibujar | Render | — |

---

## 4. Paso a paso

### 4.1 El ruido

La base es **ruido de valor fractal** (`NoiseField`): 4 octavas, lacunaridad 2,0, ganancia 0,5, e interpolación hermite `t²(3−2t)` dentro de cada celda de ruido.

No es simplex ni Perlin. Es el ruido más barato que da un resultado aceptable, y esa fue una elección deliberada: se evalúa una vez por celda por región, o sea decenas de miles de veces por segundo.

El detalle que importa es **sobre qué trabaja el hash**: coordenadas enteras de celda en `long`, mezcladas estilo splitmix64, no `float` de coordenadas absolutas.

```java
long h = seed;
h ^= x * 0x9E3779B97F4A7C15L;
h ^= z * 0xC2B2AE3D27D4EB4FL;
h ^= (long) octave * 0x165667B19E3779F9L;
h ^= h >>> 30;  h *= 0xBF58476D1CE4E5B9L;
h ^= h >>> 27;  h *= 0x94D049BB133111EBL;
h ^= h >>> 31;
return (h >>> 11) * 0x1.0p-53D;   // 53 bits: la mantisa de un double
```

Un `float` de 32 bits, a millones de bloques del origen, ya no distingue entre dos puntos separados por un bloque. Con enteros de 64 bits eso no pasa, y por eso el ruido se comporta igual en el spawn que en el borde del mundo.

### 4.2 De ruido a densidad

El valor crudo del ruido está en [0,1] y hay que convertirlo en densidad de nube. La conversión es un recorte por **cobertura**:

```
floor    = 1 − cobertura_efectiva
densidad = (crudo − floor) / (1 − floor)     si crudo > floor
densidad = 0                                 si no
```

La cobertura **desplaza el umbral** en vez de escalar el resultado. Es un detalle chico con un efecto visual grande: subir la cantidad de nubes agranda las formaciones que ya existen, en lugar de volver todo el cielo uniformemente más opaco.

La cobertura efectiva es la de la capa multiplicada por el ajuste del jugador, acotada a **0,95** como máximo. Con cobertura 1,0 no quedaría un solo punto del cielo por debajo del umbral y el resultado sería una losa de horizonte a horizonte: sin huecos no hay formas que mirar.

### 4.3 Espacio de nube y regiones

Las nubes se mueven. La forma ingenua de hacerlo sería regenerar la geometría con el viento aplicado, y es inviable: sería reconstruir el cielo entero todos los frames.

En lugar de eso, las regiones se indexan en **espacio de nube**: coordenadas con el viento ya descontado.

```
cloudX = cameraX − velocidadX × segundos × escalaDeVelocidad
cloudZ = cameraZ − velocidadZ × segundos × escalaDeVelocidad
```

La consecuencia es la que se busca: **la densidad de una región no cambia jamás**. Lo único que cambia con el tiempo es dónde se dibuja —una traslación al momento de dibujar— y cuáles caen dentro del rango.

Cada capa tiene su propia velocidad, así que cada una tiene su propia grilla de regiones. Por eso la clave de región lleva el índice de capa:

```java
record RegionKey(int layer, int x, int z)
REGION_SIZE = 256   // potencia de dos: todos los LOD la dividen exacto
```

El tiempo que se usa es el **tiempo del mundo** (`level.getGameTime() + partialTick`), no el reloj del cliente. Con el reloj del cliente las nubes saltarían al reconectar, se desincronizarían del ciclo día/noche y seguirían avanzando con el juego en pausa.

### 4.4 Qué regiones se piden

Por cada capa, cada frame:

**a) ¿Hace falta mirar esta capa?** Si el desvanecimiento vertical la dejó por debajo del umbral de descarte (0,02 de opacidad), la capa se saltea entera y no se evalúa ni una región.

**b) El radio de barrido.** Se calcula desde la distancia máxima del domo más el adelanto de movimiento:

```java
scanRange = selector.maxDistance() + prefetch.leadLength();
radius    = ceil(scanRange / 256) + 1;   // en regiones
```

Con render distance 16 y perfil Medio eso da un domo de 768 bloques, radio 4, o sea 81 claves de región evaluadas por capa y por frame. Evaluar una clave es aritmética: dos restas, una raíz cuadrada y una búsqueda en un mapa.

**c) Dos distancias, no una.** Cada región se mide dos veces: contra la posición **real** de la cámara y contra la posición **proyectada** (la real más el adelanto de movimiento).

- La **real** decide qué se dibuja. Adelantar también el dibujo movería el domo respecto de la cámara y dejaría un borde a la vista por detrás.
- La **proyectada** decide qué se genera y con qué prioridad. Es lo que pone adelante de la cola lo que el jugador va a necesitar en vez de lo que ya tiene encima.

El adelanto lo calcula `MotionPrefetch`, que estima la velocidad de la cámara con suavizado exponencial y la proyecta 1,5 segundos hacia adelante:

| Constante | Valor | Para qué |
|---|---|---|
| `HORIZON_SECONDS` | 1,5 s | Cuánto se proyecta hacia adelante |
| `MIN_SPEED` | 8 b/s | Por debajo, no se adelanta nada (caminar da 4,3; correr 5,6) |
| `MAX_LEAD` | 256 bloques | Tope del adelanto: una región |
| `MAX_PLAUSIBLE_SPEED` | 200 b/s | Por encima, la muestra se descarta (teletransporte, cambio de dimensión) |
| `SMOOTHING` | 0,2 | Un frame raro no mueve la proyección |
| `MAX_DELTA_SECONDS` | 0,5 s | Más que esto entre muestras es una pausa, no movimiento |

El tope se aplica como **factor escalar** sobre los dos ejes a la vez, no recortando X y Z por separado: así el adelanto conserva la dirección en diagonal.

**d) La prioridad.** Cinco clases, de más urgente a menos:

| Clase | Cuándo |
|---|---|
| 0 — Visible y cerca | En el frustum, dentro del tramo de detalle alto |
| 1 — Visible a media distancia | En el frustum, tramo medio |
| 2 — Visible y lejos | En el frustum, tramo lejano |
| 3 — Detrás del jugador | Fuera del frustum, pero a la espalda (se va a necesitar al girar) |
| 4 — Fuera de cámara | El resto. **No se genera.** |

La clase y la distancia se empaquetan en un solo `long` (`clase << 32 | distancia`) para ordenar la lista con una sola comparación.

**e) El envío.** La lista de pendientes se ordena de más urgente a menos y se envía hasta llenar la cola. Lo que no entra hoy se reevalúa el frame que viene, ya con la prioridad actualizada a dónde esté mirando el jugador. Por eso la cola **no** tiene prioridad interna: la prioridad cambia cada vez que el jugador gira la cabeza, así que lo correcto es recalcularla cada frame y mantener poca cosa en vuelo.

### 4.5 El cálculo de densidad

`DensityJob` es deliberadamente puro: recibe seed, capa, región y nivel de detalle, y devuelve un arreglo de `float`. No toca Minecraft, no toca OpenGL y no comparte estado mutable, así que es seguro en cualquier hilo.

```java
for (cz = 0; cz <= celdas; cz++)
  for (cx = 0; cx <= celdas; cx++)
    z = originZ + cz * ladoDeCelda;           // ESQUINA de la celda
    x = originX + cx * ladoDeCelda;           // coordenadas LOCALES a la región
    densidad[cz * esquinas + cx] = campo.densityAt(x, z);
```

Dos cosas que hacen falta subrayar:

- Se muestrea en las **esquinas** de cada celda, no en su centro. Cada vértice lleva después su propio alfa y el color se interpola por la cara, así que la silueta de la nube es continua en vez de escalonada. Cuesta una fila y una columna más de muestras —289 en vez de 256 en el nivel más fino, un 13 %— y no cuesta nada en la GPU. De paso cierra el borde entre regiones sin trabajo extra: la esquina derecha de la última celda de una región cae exactamente sobre la esquina izquierda de la primera de su vecina.
- Las coordenadas son **locales a la región**. Junto con el hash entero, es la otra mitad de la estrategia de origen flotante: el ruido nunca ve un número grande en punto flotante.

El resultado lleva además la cuenta de celdas no vacías, que sirve para dos cosas: saltear por completo las regiones de cielo despejado, y estimar cuántos cuádruples va a costar construir la malla antes de construirla.

La cola tiene un tope de **24 trabajos en vuelo**. Los hilos son *daemon*, tantos como diga la configuración o la mitad de los núcleos por defecto, y corren con **prioridad `NORM_PRIORITY − 2`**: generar nubes nunca debe competir con el hilo de render.

### 4.6 La construcción de la malla

Aquí se materializa la técnica central. Por cada **corte horizontal** se emite **un cuádruple por celda** cuya densidad supera el umbral de ese corte.

**Las alturas: una escalera compartida.** Todos los niveles de detalle reparten sus cortes sobre la misma escalera de 8 peldaños, y los niveles gruesos usan un subconjunto exacto de las alturas del fino.

```java
LADDER_STEPS = 8;
step = max(1, LADDER_STEPS / cortes);
rung = index * step + (step == 1 ? 0 : 1);
t    = (rung * 2 + 1) / 16.0;          // posición vertical normalizada, en (0,1)
altura = baseDeLaCapa + t * espesorDeLaCapa;
```

Para la capa baja (base 172, espesor 16) eso da:

| Cortes | Alturas en bloques |
|---|---|
| 8 | 173 · 175 · 177 · 179 · 181 · 183 · 185 · 187 |
| 4 | 175 · 179 · 183 · 187 |
| 2 | 175 · 183 |

**El umbral: función de la altura.** El medio de la capa acepta casi cualquier densidad; los extremos exigen densidad alta. Por eso solo el núcleo de una formación llega arriba y abajo, y el conjunto se lee como algo redondeado en vez de como láminas apiladas.

```java
desdeElCentro = |t − 0.5| * 2;
umbral = 0.06 + 0.30 * desdeElCentro^1.35;
```

Con ocho cortes: **0,311 · 0,219 · 0,140 · 0,078 · 0,078 · 0,140 · 0,219 · 0,311**.

El rango está elegido contra la distribución real del campo, no a ojo. Medido sobre 640.000 muestras de la capa baja: el 97 % de las celdas con nube quedan por debajo de 0,6 de densidad y la media es 0,19. Con estos umbrales, los ocho cortes cubren el 21 %, 38 %, 56 % y 72 % de las celdas con nube, y de ahí hacia abajo en espejo — un perfil repartido, en vez de unos pocos cortes haciendo todo el trabajo.

El umbral depende de la **altura normalizada**, no del índice del corte. Es una condición para que la escalera sirva de algo: dos cortes que caen a la misma altura desde niveles de detalle distintos tienen que recortar la nube igual.

**El borde suave.** Una celda que apenas supera el umbral no aparece de golpe: se desvanece a lo largo de un rango de 0,38 de densidad, con una curva suave.

```java
u = (densidad − umbral) / 0.38;
alfaDeCelda = u <= 0 ? 0 : u >= 1 ? 1 : u * u * (3 − 2 * u);
```

Dos propiedades importan acá, y las dos apuntan a lo mismo:

- **El ancho, 0,38, es casi el triple del salto de umbral entre dos cortes vecinos** (0,09 con ocho cortes, 0,17 con cuatro). Así la silueta de un corte se superpone con la del siguiente en vez de terminar justo donde esa empieza. Sin superposición, la pila vista de canto se lee como una escalera de terrazas: un escalón por corte.
- **La curva es hermite y no una rampa recta.** Una rampa recta es continua pero su derivada no: hay un quiebre donde la nube empieza y otro donde satura. Un quiebre en el alfa es lo que el ojo lee como una línea. Con `u²(3−2u)` la derivada se anula en los dos extremos.

**La opacidad: constante, sea cual sea el nivel de detalle.** Cada corte lleva un peso según su altura —los de los extremos aportan poco menos de seis décimos de lo que aporta el central— y la escalera se normaliza para que la pila completa converja siempre a la misma opacidad:

```java
peso  = 1 − 0.55 * desdeElCentro²
alfa_i = s * peso_i,  con s tal que  producto(1 − alfa_i) = 1 − 0.92
```

El factor común se resuelve por bisección, una vez por malla construida. Con ocho cortes eso da alfas de 0,190 en los extremos y 0,325 en el centro; con cuatro, 0,326 y 0,519.

El peso es lo que hace que la capa **se desvanezca hacia arriba y hacia abajo** en vez de terminar en un canto duro. Un canto duro, visto de canto, es justamente lo que se lee como una lámina.

**El color: también constante entre niveles.** Que la opacidad coincida no alcanza. Cada nivel reparte sus cortes sobre alturas distintas y con alfas distintos, así que la mezcla de sombreados que sale de la pila también difiere: medido a densidad saturada, una región de ocho cortes quedaba **1,7 niveles de gris** más oscura que su vecina de cuatro. Como el límite entre dos regiones es recto y mide 256 bloques, esa diferencia se lee como un panel.

La corrección es un factor sobre el sombreado, tabulado por nivel y por densidad, que lleva la columna entera al color que daría el nivel más detallado:

```
correccion = pesoDeColor(8 cortes, densidad) / pesoDeColor(n cortes, densidad)
```

Con una salvaguarda que importa: **solo se aplica donde las dos pilas ya tapan lo mismo.** A densidades bajas un nivel puede no tener ningún corte activo mientras el otro sí, y ahí subirle el brillo al que tapa menos lo aleja en vez de acercarlo. La corrección se desvanece a medida que las opacidades se separan, y por encima de 0,02 de diferencia se apaga del todo.

| | Costura cercana (8→4 cortes) | Costura lejana (4→2 cortes) |
|---|---|---|
| Peor salto, 0.2.5 | 0,99 niveles | 3,67 niveles |
| Peor salto, 0.2.6 | **0,67** | **1,65** |
| Con nube densa | **0,000** | **0,001** |

La pila no llega a 1 a propósito: una nube que tapa el cielo por completo deja de leerse como volumen. Y como la opacidad total no cambia entre niveles, **el nivel de detalle cambia la estructura interna de la nube y no su densidad aparente**, que es lo que debe hacer un LOD.

**El sombreado.** Dos términos, los dos baratos, horneados en el color del vértice:

```java
vertical   = 0.78 + 0.22 * t;        // la base recibe menos luz que el techo
porDensidad = 1 − 0.12 * densidad;   // lo más denso se oscurece un poco más
sombra = vertical * porDensidad;
```

Igual que el umbral: en función de la altura normalizada, no del índice del corte.

**El buffer.** Cuatro vértices por cuádruple, formato `POSITION_COLOR`, sin textura:

```java
builder.vertex(x0, y, z0).color(r, g, b, a).endVertex();
builder.vertex(x0, y, z1).color(r, g, b, a).endVertex();
builder.vertex(x1, y, z1).color(r, g, b, a).endVertex();
builder.vertex(x1, y, z0).color(r, g, b, a).endVertex();
```

Las coordenadas son locales a la región; la posición en el mundo se resuelve con una traslación al dibujar. El resultado se sube a un `VertexBuffer` de uso `STATIC` y no se vuelve a tocar hasta que la región se desaloje o cambie de nivel de detalle.

El campo de densidad **no se conserva** después de construir la malla: se puede recalcular en cualquier momento a partir de la seed, y guardarlo solo gastaría memoria. Lo que sí se conserva es el buffer, porque reconstruirlo es lo caro.

Una región puede quedar **vacía** —cielo despejado en esa zona— y se cachea igual, con el buffer en nulo, para no recalcularla en cada frame.

### 4.7 El presupuesto por frame

El renderer no genera "todo lo que haga falta": genera lo que entra en el presupuesto y deja el resto para los frames siguientes, respetando el orden de prioridad. Son tres topes:

| Tope | Qué limita |
|---|---|
| Regiones por frame | Cuántas mallas se construyen y se suben a la GPU |
| Cuádruples por frame | Cuánta geometría se construye, sin importar en cuántas regiones |
| Regiones en caché | Cuántas se mantienen vivas antes de empezar a desalojar |

Con dos salvaguardas:

- **Siempre entra al menos una.** Si nada cabe en el presupuesto pero todavía no se generó nada en este frame, se admite una región igual. Sin esto, un presupuesto de cuádruples demasiado ajustado dejaría el cielo vacío para siempre en vez de llenarse despacio.
- **Lo que no entra no se tira.** El cálculo de densidad ya está pagado, así que el resultado se guarda y se construye en el frame siguiente, antes que nada nuevo. La lista de diferidos tiene un tope de 32; lo que se pasa de ahí se descarta y se vuelve a pedir cuando haga falta.

### 4.8 El dibujo

**El orden.** De lejos a cerca. Con transparencia el orden cambia el resultado, así que la lista de dibujo se ordena por distancia descendente antes de emitir nada.

**El estado de render.** Todas las decisiones de transparencia en un solo lugar:

| Estado | Valor | Por qué |
|---|---|---|
| Mezcla | Translúcida normal | — |
| Máscara de escritura | **Solo color** | Las nubes son volumen, no superficie: si escribieran profundidad, los cortes se ocultarían entre sí |
| Prueba de profundidad | `LEQUAL` | Sí leen profundidad, así que las tapan las montañas y las estructuras |
| Culling de caras | **Ninguno** | Los cortes son planos horizontales y se ven desde arriba y desde abajo |
| Textura | Ninguna | El color viene por vértice |
| Shader | `POSITION_COLOR` del juego | Sin shaders propios: el mod se queda en la ruta de render estándar de Forge |

**El bucle.** Un `draw call` por región. Para cada una:

```java
poseStack.translate(
    region.originX() + desplazamientoDelViento − camaraX,
    −camaraY,
    region.originZ() + desplazamientoDelViento − camaraZ);

RenderSystem.setShaderColor(
    colorDeNubeDelNivel.x * dispersion,
    colorDeNubeDelNivel.y * dispersion,
    colorDeNubeDelNivel.z * dispersion,
    opacidadDeLaRegion);

buffer.bind();
buffer.drawWithShader(...);
```

Tres cosas se resuelven acá y no en la malla, porque cambian todos los frames mientras la malla no:

1. **La posición**, incluido el desplazamiento del viento.
2. **El tinte del momento del día**, que se toma del propio nivel (`level.getCloudColor`), así que las nubes de Atmosia se tiñen con el atardecer igual que las del juego.
3. **La opacidad**, que es el desvanecimiento vertical multiplicado por el de distancia.

**Dispersión hacia adelante.** El borde luminoso de las nubes a contraluz: cuando la vista apunta hacia el sol, el color se multiplica por hasta 1,35.

```java
dispersion = sol·vista > 0 ? 1 + 0.35 * (sol·vista)^6 : 1
```

El exponente 6 es alto a propósito: el efecto aparece solo cuando se mira bastante hacia el sol, no de forma difusa por medio cielo.

### 4.9 La caché y el desalojo

La caché es un mapa de `RegionKey` a malla. Cada vez que una región se evalúa —se dibuje o no— se le marca el número de frame actual.

Al final del frame, si la caché superó su tamaño máximo, se ordena por frame de último uso y se cierran las más viejas hasta volver al tope. Es un LRU exacto, y se puede hacer exacto porque el orden de magnitud es de cientos de entradas, no de millones.

**El cambio de nivel de detalle no abre agujeros.** Cuando una región ya cacheada pasa a necesitar otro nivel, se encola la versión nueva pero **se sigue dibujando la vieja** hasta que la nueva esté lista. Dejar de dibujarla en el momento de encolar dejaría un hueco en el cielo justo al cruzar el umbral de distancia.

---

## 5. Los tres niveles de detalle

En esta arquitectura el LOD no es "menos vértices de la misma forma". Es **menos cortes verticales y celdas más grandes**.

| Nivel | Cortes por capa | Lado de celda | Capas dibujadas | Celdas por región | Tope de cuádruples por región |
|---|---|---|---|---|---|
| **Alto** | 8 | 16 bloques | 3 | 16 × 16 = 256 | 2.048 |
| **Medio** | 4 | 16 bloques | 3 | 16 × 16 = 256 | 1.024 |
| **Bajo** | 2 | 32 bloques | 2 | 8 × 8 = 64 | 128 |

Los cortes son lo que da la profundidad volumétrica y lo que cuesta relleno de píxeles, así que reducirlos a distancia es exactamente donde está el ahorro. **La celda no crece tanto como el ahorro tentaría**: una celda muy grande se lee como una sábana rectangular en el cielo por lejos que esté, y eso es un defecto visual, no un ajuste de calidad.

A partir del nivel bajo se dibujan solo dos capas en vez de tres: la más alta es la que menos se nota al desaparecer, así que es la primera que se va.

**Los tramos** se derivan de la distancia máxima del domo, que a su vez sale del render distance del jugador:

```
distanciaMáxima = max(512, renderDistance × 16 × multiplicadorDelPerfil)

detalle alto   hasta el 20 % de la distancia máxima
detalle medio  hasta el 53 %
detalle bajo   hasta el 100 %
desvanecimiento por distancia desde el 85 %
```

El piso de 512 bloques es generoso a propósito: un domo de nubes corto se nota muchísimo más que uno largo, porque el borde queda dentro del campo de visión y el cielo se ve recortado.

Con render distance 16 y perfil Medio (×3,0): domo de **768 bloques**, detalle alto hasta 154, medio hasta 407, bajo hasta 768, y el borde empieza a desvanecerse a los 653.

El perfil gráfico impone además un **tope de detalle**, que no acorta el domo ni cambia el tamaño de celda: solo impide que las regiones cercanas usen el nivel más caro. Y nunca funciona al revés —un perfil alto no puede forzar detalle donde la distancia no lo justifica.

---

## 6. Las tres capas

No son tres planos independientes: **comparten el mismo campo de ruido** con escalas y cortes distintos, así que donde coinciden la densidad se acumula y el conjunto se lee como una masa más profunda en vez de como tres láminas superpuestas.

| Capa | Altura base | Espesor | Cobertura | Escala de ruido | Velocidad (X, Z) | Color |
|---|---|---|---|---|---|---|
| **Baja** | 172 | 16 | 0,42 | 300 | 0,6 · 0,15 | gris apenas más oscuro |
| **Media** | 192 | 20 | 0,36 | 420 | 1,0 · 0,25 | gris neutro |
| **Alta** | 216 | 14 | 0,26 | 620 | 1,6 · 0,40 | gris levemente azulado |

La escala de ruido es cuántos bloques abarca una unidad de ruido: más alto, formaciones más grandes. Las velocidades son bloques por segundo, y **las tres son distintas a propósito**: es el paralaje entre capas lo que produce la sensación de profundidad cuando uno mira el cielo un rato.

---

## 7. Los dos desvanecimientos

Ningún corte es binario. En los dos casos la geometría se descarta de verdad recién cuando el desvanecimiento ya la dejó prácticamente invisible, de forma que el propio desvanecimiento tape el corte real.

**Por distancia.** Empieza en el último 15 % del domo y llega a cero en el borde. Es lo que evita que el límite del mundo de nubes aparezca como un recorte recto.

**Por altura.** Se mide la distancia vertical de la cámara a la capa:

- **Estar dentro de la capa nunca atenúa.** Es el caso en que más se la ve.
- Fuera de ella, la atenuación empieza a los **180 bloques** de distancia vertical y llega a cero a los **420**, con suavizado `t²(3−2t)` para que ni el arranque del desvanecimiento se note como un quiebre.
- **Mirar hacia la capa la mantiene visible más lejos**: si la cámara apunta hacia ella, la distancia efectiva se reduce hasta la mitad, proporcional al ángulo hasta los 60°. La dirección de la vista es parte del culling, no solo la altura absoluta.

Por debajo de 0,02 de opacidad la capa se saltea entera, antes de evaluar una sola región.

---

## 8. Perfiles y configuración en caliente

Tres perfiles probados más uno manual. Se mueven en los dos ejes que de verdad cuestan, y en ninguno más: **cortes por capa** y **alcance del domo**.

| Perfil | Multiplicador de distancia | Tope de detalle | Regiones/frame | Cuádruples/frame | Regiones en caché |
|---|---|---|---|---|---|
| **Bajo** | 1,5 | Medio (4 cortes) | 1 | 8.000 | 128 |
| **Medio** | 3,0 | Alto (8 cortes) | 2 | 24.000 | 384 |
| **Alto** | 4,5 | Alto (8 cortes) | 4 | 64.000 | 768 |
| **Personalizado** | — | — | *manda el archivo de configuración* | | |

Un solo lugar del código resuelve "perfil o archivo", así que no hay forma de que una parte del renderer lea el perfil y otra los valores sueltos.

**Qué pasa al cambiar un ajuste con el juego andando.** La distinción que importa es cuál de los dos obliga a tirar la caché:

| Ajuste | Efecto | Caché |
|---|---|---|
| **Perfil gráfico** | Cambia qué nivel de detalle le toca a cada región | **Se conserva.** El renderer lo detecta región por región y reemplaza sin huecos |
| **Cantidad de nubes** | Cambia la densidad misma | **Se vacía.** Las mallas en memoria describen un cielo que ya no es el pedido |

Los cálculos que ya estaban en vuelo cuando se cambió la cantidad de nubes se descartan al llegar: se los reconoce porque llevan anotada la cobertura con la que se calcularon.

---

## 9. Integración con el juego

**El enganche de dibujo** es el evento de render del nivel, en la etapa posterior a los bloques translúcidos: las nubes quedan ya ordenadas respecto del terreno, y la lluvia y la nieve siguen quedando por delante, que es donde el jugador espera verlas.

**El enganche de tick** corre una vez por tick y decide si Atmosia debe estar activo: cambios de mundo, de configuración o de mods.

**La supresión de las nubes vanilla** no usa mixin y no toca `LevelRenderer`. Apaga el ajuste de nubes del propio juego mientras Atmosia dibuja, y lo devuelve como estaba al desactivarse. El valor se **reaplica una vez por tick**, comparando el valor real y volviéndolo a poner si algo lo movió: cuesta una comparación de enums por tick y cubre todas las vías por las que el ajuste se puede perder —el menú de opciones al cerrarse, una recarga del archivo, otro mod que lo toque después.

**El interruptor principal** tiene tres estados y no dos, porque "apagar las nubes" es ambiguo:

| Modo | Atmosia dibuja | Vanilla suprimida |
|---|---|---|
| **Atmosia** | Sí | Sí |
| **Vanilla** | No | No |
| **Ninguna** | No | Sí |

**La convivencia con otros mods.** Si hay un mod que también reemplaza las nubes, o un mod de shaders instalado, Atmosia **cede el cielo entero** —nubes vanilla incluidas— en vez de pelearlo. Competir por el render del cielo produce errores que nadie puede reproducir, y un pack de shaders ya dibuja sus propias nubes.

**La seed.** Si hay una fija en la configuración, se usa esa. Si no, se deriva de un dato estable que el cliente sí conoce: la dirección del servidor —o `local`— más el nombre de la dimensión. La consecuencia está asumida: dos jugadores del mismo servidor no ven necesariamente las mismas nubes. Quien quiera igualarlas puede fijar la misma seed a mano.

**Cliente o servidor.** Todo Atmosia es de cliente. Se instala en la carpeta `mods` del jugador, funciona en cualquier servidor incluidos los vanilla, y el servidor no lo necesita.

---

## 10. Tablas de referencia

### 10.1 Constantes del sistema

| Constante | Valor | Dónde |
|---|---|---|
| Lado de región | 256 bloques | `RegionKey` |
| Octavas de ruido | 4 (lacunaridad 2,0 · ganancia 0,5) | `NoiseField` |
| Peldaños de la escalera de alturas | 8 | `DensityField` |
| Opacidad de la pila de cortes | 0,92 | `DensityField` |
| Cobertura máxima | 0,95 | `DensityField` |
| Suavidad del borde | 0,38 de densidad, curva hermite | `DensityField` |
| Sombreado de la base | 0,78 (techo: 1,0) | `DensityField` |
| Umbral de densidad | 0,06 + 0,30 · desdeElCentro^1,35 | `DensityField` |
| Peso vertical del corte | 1 − 0,55 · desdeElCentro² | `DensityField` |
| Dispersión hacia adelante | hasta ×1,35, exponente 6 | `DensityField` |
| Tramos de LOD | 20 % · 53 % · 100 % | `LodSelector` |
| Piso del domo | 512 bloques | `LodSelector` |
| Desvanecimiento por distancia | último 15 % | `LodSelector` |
| Desvanecimiento vertical | 180 → 420 bloques | `VerticalFade` |
| Umbral de descarte por opacidad | 0,02 | `VerticalFade` |
| Trabajos en vuelo | 24 | `GenerationQueue` |
| Prioridad de los hilos de trabajo | `NORM_PRIORITY − 2` | `GenerationQueue` |
| Resultados diferidos | 32 | `CloudRenderer` |
| Bytes por cuádruple en GPU | 64 (4 vértices × 16) | `RegionMesh` |

### 10.2 Qué hace cada archivo

| Archivo | Responsabilidad |
|---|---|
| `core/NoiseField` | Ruido de valor fractal determinista |
| `core/DensityField` | Ruido → densidad; alturas, umbrales, alfa y sombreado de los cortes |
| `core/CloudLayerDef` | Los parámetros de las tres capas y el desplazamiento del viento |
| `core/RegionKey` | Identidad de una región en espacio de nube |
| `core/LodLevel` | Los tres niveles: cortes, lado de celda, capas |
| `core/LodSelector` | Distancia del domo, tramos de LOD, desvanecimiento por distancia |
| `core/VerticalFade` | Desvanecimiento por altura de cámara y por dirección de vista |
| `core/RegionPriority` | Las cinco clases de prioridad y la clave de orden |
| `core/CloudBudget` | Los topes por frame y su contabilidad |
| `core/MotionPrefetch` | Velocidad estimada y adelanto de generación |
| `core/QualityProfile` | Los tres perfiles y su resolución contra el archivo |
| `core/CloudMode` | El interruptor de tres estados |
| `client/CloudRenderer` | El bucle del frame: barrido, cola, presupuesto, dibujo, desalojo |
| `client/DensityJob` | El cálculo de densidad de una región, ejecutable en cualquier hilo |
| `client/GenerationQueue` | Los hilos de trabajo y la entrega de resultados |
| `client/RegionMeshBuilder` | Densidad → cuádruples → `VertexBuffer` |
| `client/RegionMesh` | La geometría subida y su estado de caché |
| `client/AtmosiaRenderType` | Las decisiones de transparencia, en un solo lugar |
| `client/AtmosiaClientEvents` | Los enganches de render y de tick |
| `client/AtmosiaClient` | Si el mod debe estar activo; la seed; la convivencia con otros mods |
| `client/VanillaCloudSuppressor` | Apagar y restaurar el ajuste de nubes del juego |
| `AtmosiaConfig` | Toda la configuración de cliente |

---

*Versión 0.2.4. Para las ventajas y los costos medidos de este método, ver `docs/sistema-de-nubes.md`.*
