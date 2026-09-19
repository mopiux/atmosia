# Artefactos visuales: diagnóstico medido y corrección

*Sobre las capturas del 18–19/09/2026, tomadas volando con elytra por debajo de la capa de nubes.*

*Este documento revisa un diagnóstico previo (`atmosia-artefactos-y-soluciones.md`) que identificó bien los dos síntomas y les atribuyó causas que el código no tiene. Acá están las causas reales, verificadas ejecutando el núcleo, y las correcciones aplicadas en **0.2.2**.*

---

## Resumen

| Síntoma | Causa que se propuso | Causa real |
|---|---|---|
| Bandas grises en los bordes | Sombreado acumulándose multiplicativamente (0,75⁸ ≈ 0,10) | El corte más bajo es **más oscuro que el cielo** sobre el que se mezcla |
| "Recarga" de nubes volando | Latencia de generación, cola reactiva | **La opacidad cambiaba 20 puntos** en cada cambio de nivel de detalle |

Los dos síntomas son reales y están corregidos. Ninguna de las dos causas propuestas existía en el código.

---

## Artefacto 1 — Bandas grises

### Por qué la causa propuesta no puede ser

El diagnóstico previo decía que el factor de sombreado por altura se aplica a cada corte y se acumula multiplicativamente al mezclarlos: `0,75 × 0,75 × ... = 0,75⁸ ≈ 0,10`, casi negro.

Eso sería cierto **con mezcla multiplicativa** (`GL_DST_COLOR, GL_ZERO`). Atmosia usa mezcla alfa estándar (`GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA`), donde cada corte aporta `color × alfa + fondo × (1 − alfa)`. Eso es un **promedio ponderado**, no un producto: el resultado nunca queda más oscuro que el más oscuro de los cortes.

La segunda causa propuesta —que `VerticalFade` multiplique el RGB además del alfa— tampoco aplica. En `CloudRenderer.draw` el desvanecido entra **solo por el canal alfa** de `RenderSystem.setShaderColor`; el RGB lleva el tinte del día y el brillo del sol, que no tienen relación con el fade. La regla que el documento recomienda instaurar ya estaba instaurada.

### Qué pasaba de verdad

Componiendo a mano la pila de la capa baja sobre un cielo diurno, con las constantes de la 0.2.1:

| | RGB compuesto | Brillo medio |
|---|---|---|
| Cielo de fondo | 0,620 / 0,710 / 0,850 | 0,727 |
| Tras 1 corte | 0,543 / 0,587 / 0,659 | 0,596 |
| Tras 2 cortes | 0,532 / 0,554 / 0,597 | **0,561** |
| Tras 4 cortes | 0,581 / 0,591 / 0,615 | 0,596 |
| Tras 8 cortes | 0,740 / 0,749 / 0,774 | 0,754 |

El color propio del corte más bajo era **0,480 / 0,486 / 0,502**: un gris medio, *más oscuro que el cielo diurno*. Mezclarlo al 55% sobre un cielo claro arrastra el resultado hacia abajo.

Eso explica exactamente el patrón de las capturas. Donde se superponen **pocos** cortes —el borde de toda formación vista desde abajo— el compuesto cae 0,166 por debajo del cielo y se lee como una banda gris. Donde se superponen los ocho, los cortes de arriba (sombra hasta 1,0) lo compensan y vuelve a aclarar.

**El defecto estaba en los bordes precisamente porque ahí hay menos cortes, no más.** El diagnóstico previo tenía la dirección invertida: dedujo que más cortes en la línea de visión oscurecían, cuando era al revés.

### Un segundo hallazgo, del mismo cálculo

La opacidad acumulada de una pila completa:

| Nivel | Cortes | Alfa por corte | Opacidad de la pila |
|---|---|---|---|
| `HIGH` | 8 | 0,55 | **0,998** |
| `MEDIUM` | 4 | 0,55 | 0,959 |
| `LOW` | 2 | 0,55 | 0,798 |

Con ocho cortes la pila llegaba a **0,998: el núcleo de una formación era una pared opaca**. Es lo contrario de lo que el sistema de cortes existe para producir. Un sistema pensado para dar volumen translúcido estaba produciendo, en su nivel de mayor detalle, una superficie sólida.

### Corrección

**1. El alfa por corte se deriva de cuántos cortes hay**, para que la pila llegue siempre a la misma opacidad:

```
alfa_corte = 1 − (1 − opacidad_objetivo)^(1/cortes)
```

Con objetivo 0,92: 0,271 con ocho cortes, 0,468 con cuatro, 0,717 con dos. La pila da 0,92 en todos los casos.

**2. `BOTTOM_SHADE` pasa de 0,62 a 0,78.** Conserva el gradiente que hace que una nube se lea como nube —la base más oscura que el techo— sin que el compuesto parcial se hunda.

Resultado medido, con la misma composición:

| | Caída máxima por debajo del cielo |
|---|---|
| 0.2.1 | 0,166 |
| 0.2.2 | **0,050** |

La caída no se elimina del todo, y **no debe eliminarse**: una nube vista desde abajo *es* más oscura que el cielo. Si no lo fuera se leería como niebla. Lo que era defecto es la magnitud.

Ambas cosas quedaron fijadas con pruebas que comparan contra los valores viejos, así que una regresión se detecta sola.

---

## Artefacto 2 — "Recarga" de nubes volando

### La causa que faltaba

El diagnóstico previo lista cuatro causas plausibles y ordena el trabajo poniendo el prefetch direccional primero. La causa dominante no está en la lista, y sale de la misma tabla de arriba:

**Cada cambio de nivel de detalle cambiaba la opacidad de la nube en hasta 20 puntos porcentuales.** De 0,998 a 0,959 a 0,798 al cruzar un umbral de distancia. No es un cambio de nitidez ni de silueta: es un cambio de **brillo**, que es de lo más visible que hay, y ocurría en un solo frame.

La corrección de opacidad constante del Artefacto 1 elimina esto por completo, sin tocar la cola de prioridad. El nivel de detalle ahora cambia la estructura interna de la nube y no su densidad aparente, que es lo que un LOD debe hacer.

### Un nivel de detalle que no existía

Al verificar los umbrales apareció otra cosa. El sistema declaraba cuatro niveles, pero el tramo de `LOW` llegaba hasta el borde del domo:

```
highUntil   = 0,20 × max
mediumUntil = 0,53 × max
lowUntil    = max          ← mismo valor que maxDistance
```

Como `levelFor` ya devuelve `null` más allá de `maxDistance`, **`MINIMAL` era inalcanzable**. Confirmado recorriendo todas las distancias de 0 a 768: solo aparecen `HIGH`, `MEDIUM` y `LOW`.

Y era código muerto **dañino**: `MINIMAL` usaba celdas de 64 bloques, exactamente el tamaño que producía las sábanas rectangulares que la 0.0.1 corrigió. Si alguien hubiera "arreglado" el umbral para que se usara, habría reintroducido el defecto.

Se eliminó el nivel. Hay una prueba que verifica que todos los niveles declarados son alcanzables, para que no vuelva a pasar.

### Prefetch direccional

Es la mejor idea del documento previo y está implementada, aunque ahora como segunda causa y no como primera.

`MotionPrefetch` estima la velocidad de la cámara con suavizado exponencial y proyecta la posición 1,5 segundos hacia adelante. Esa posición proyectada decide **qué se genera y con qué prioridad**; la posición real sigue decidiendo **qué se dibuja**. Adelantar también el dibujo movería el domo respecto de la cámara y dejaría un borde visible por detrás.

El radio de barrido crece con el adelanto: sin eso no habría nada nuevo que encontrar, porque las regiones que se quieren anticipar están por definición fuera del domo. Eso cubre también el punto 2 del documento previo (radio escalado con velocidad) sin un mecanismo aparte.

Tres salvaguardas, todas con prueba:

| Situación | Comportamiento |
|---|---|
| Caminando (4,3 b/s) | No adelanta nada. Por debajo de 8 b/s el margen ya sobra y reordenar la cola sería ruido. |
| Elytra (45 b/s) | Adelanta 67,5 bloques en la dirección del vuelo. |
| Teletransporte | **No adelanta nada.** Un salto de posición dividido por el delta de tiempo da una velocidad absurda; por encima de 200 b/s la muestra se descarta entera. |
| Pausa del juego | No deja velocidad residual. |
| 190 b/s sostenidos | Se acota a 256 bloques —una región— sin torcer la dirección. |

El descarte por velocidad implausible salió de una prueba que falló: la primera versión acotaba el adelanto a 256 bloques tras un teletransporte, en una dirección que no significaba nada. Acotar no alcanzaba; hay que descartar.

### Lo que no hizo falta tocar

Coincide con el documento previo: el núcleo de ruido, las capas, el paralaje y el tamaño del presupuesto por frame no son la causa y no se tocaron. Subir el presupuesto habría tratado el síntoma.

Tampoco hizo falta el crossfade de LOD. Estaba propuesto como mitigación y queda disponible para la 0.3.0, donde es mucho más barato, pero la opacidad constante resuelve la parte visible del problema sin costo adicional.

---

## Sobre el orden de trabajo

El documento previo recomienda corregir estos artefactos **antes** de migrar al sistema de textura horneada, para poder demostrar por separado qué mejoró por el arreglo y qué por el rediseño. La recomendación es correcta y es lo que se hizo.

Con un matiz que refuerza el argumento: la causa real del Artefacto 1 **no habría desaparecido sola en la 0.3.0**. El documento previo lo daba por hecho —"la Causa 1 deja de ser posible por construcción"— pero eso valía para la acumulación multiplicativa, que no existía. Un texel horneado con el sombreado de la base seguiría siendo más oscuro que el cielo. La corrección del `BOTTOM_SHADE` hay que llevarla al horneado igual.

---

## Qué mirar en la próxima prueba

1. **Desde abajo, en ángulo rasante**, igual que las capturas que originaron esto. Las bandas grises en los bordes deberían haber bajado mucho, no desaparecido.
2. **El núcleo de una formación grande.** Antes era opaco; ahora debería dejar pasar algo de cielo.
3. **Volando con elytra en línea recta**, mirando de costado. El cambio de nivel de detalle ya no debería verse como un cambio de brillo.
4. **El benchmark de nuevo.** Quitar `MINIMAL` y subir el alfa de los cortes de `LOW` cambia el costo de relleno a distancia, así que las cifras de `full_coverage` pueden moverse en cualquier dirección.
5. **Y la línea base de vanilla**, que sigue pendiente.
