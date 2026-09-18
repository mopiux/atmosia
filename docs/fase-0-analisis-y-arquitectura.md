# Atmosia — Fase 0: análisis, arquitectura propuesta y verificaciones pendientes

Estado: **propuesta, esperando confirmación explícita.** Según la Sección 15 del documento de
diseño, esta fase no escribe código de gameplay ni de renderizado. No se ha creado scaffolding
del mod todavía: eso pertenece a la Fase 1 y requiere aprobación previa de este documento.

Referencia: `docs/Atmosia_Documento_de_Diseno_v1.1.docx`.

---

## 1. Qué se pudo verificar y qué no

Esta Fase 0 se ejecutó en un entorno sin acceso a Minecraft ni a Forge. Concretamente:

- `maven.minecraftforge.net` y `piston-meta.mojang.com` están bloqueados por la política de red
  del entorno (403 en el CONNECT del proxy). No se pudo descargar el MDK, ni el cliente, ni
  generar fuentes decompiladas.
- No se puede compilar, ejecutar el juego ni observar un solo píxel renderizado.

Consecuencia directa: **todo lo que en este documento se refiere al código real de 1.20.1 es
hipótesis a verificar, no dato confirmado.** Está marcado como tal y agrupado en la Sección 10,
con la verificación exacta a realizar y qué decisión dispara cada resultado posible.

Lo que sí se pudo hacer, y es la mayor parte del entregable que pide la Sección 15: las
decisiones de arquitectura, con sus alternativas descartadas, sus motivos y sus riesgos.

---

## 2. Versiones y toolchain (a confirmar)

| Elemento | Propuesta | Estado |
|---|---|---|
| Minecraft | 1.20.1 | fijado por el documento |
| Forge | rama 47.x (última recomendada para 1.20.1) | **verificar la versión exacta** |
| Mappings | official (Mojang) para 1.20.1, con Parchment opcional para nombres de parámetros | a confirmar |
| Java | 17 | requisito de 1.20.1 |

La versión exacta de Forge debe leerse de `files.minecraftforge.net` en un entorno con red y
fijarse en `gradle.properties` antes de la Fase 1. No se fija aquí un número inventado.

---

## 3. Reemplazo del render vanilla: tres rutas, en orden de preferencia

El documento exige (Sección 13) eliminar por completo las nubes vanilla sin modificar archivos
de Minecraft. Hay tres rutas posibles, y deben evaluarse **en este orden**, porque la primera
que funcione elimina riesgo de compatibilidad en vez de agregarlo.

### Ruta A — Efectos de dimensión, sin mixin (preferida si resulta viable)

Hipótesis: en 1.20.1 el render de nubes vanilla está condicionado por la altura de nubes que
declara la configuración de efectos de dimensión (`DimensionSpecialEffects`), y una altura
inválida (NaN) equivale a "esta dimensión no tiene nubes" — que es el mecanismo por el cual el
Nether y el End no las dibujan. Si eso es así, suprimir las nubes vanilla no requiere tocar
`LevelRenderer` en absoluto.

Ventaja: es el único camino que no compite con ningún otro mod por el mismo punto de inyección.

Riesgo conocido incluso si funciona: la tabla de efectos por dimensión es un recurso compartido
y global. Sustituir la entrada del Overworld es una operación que otros mods también hacen
(mods de cielo, de clima, de dimensiones). Si se elige esta ruta, hay que decidir cómo se
comporta Atmosia cuando encuentra que otro mod ya sustituyó esa entrada: **respetarla y
desactivarse**, nunca pisarla en silencio.

### Ruta B — Evento de Forge

`RenderLevelStageEvent` expone etapas del render del mundo (`AFTER_SKY`,
`AFTER_SOLID_BLOCKS`, `AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS`, `AFTER_CUTOUT_BLOCKS`,
`AFTER_TRANSLUCENT_BLOCKS`, `AFTER_PARTICLES`, `AFTER_WEATHER`). Sirve para **dibujar** las
nubes propias en el momento correcto, pero por sí solo no cancela el render vanilla: no hay,
hasta donde se pudo determinar, una etapa ni un evento específico de nubes en 1.20.1.

Es decir: la Ruta B resuelve la mitad "dibujar", no la mitad "suprimir". Se combina con A o C.

### Ruta C — Mixin (último recurso)

Solo si A resulta inviable. Debe apuntar al método exacto que dibuja las nubes, usar
`@Inject` con `cancellable = true` en su cabecera, nunca `@Overwrite`, y documentar el punto de
inyección exacto en el propio repositorio. Es el punto de fricción clásico con shader packs y
mods de optimización, y por eso es la última opción y no la primera.

---

## 4. Decisión de arquitectura central: representación geométrica (Sección 3.1)

**Decisión propuesta: campo de densidad renderizado como slices horizontales alfa-blended,
generados a partir de una textura de densidad por región, compuesta a resolución reducida.**

Cómo funciona, de arriba hacia abajo:

1. Por región, el ruido procedural produce un **campo de densidad 2D** (cobertura y espesor)
   por capa, en coordenadas locales a la región.
2. Las capas se **combinan en una textura de densidad** por región antes de renderizar. Esa
   combinación lee qué capas están activas en el LOD de ese rango, en vez de combinar siempre
   las tres.
3. La región se dibuja como **N quads horizontales apilados** que muestrean esa textura con un
   corte de densidad distinto por altura, produciendo la silueta volumétrica.
4. El conjunto se dibuja sobre un **render target propio a resolución reducida** y se compone
   escalado sobre la escena.

Por qué esta ruta:

- **Resuelve la tensión entre layer baking y LOD por capa** (Sección 3) tomando la tercera de
  las opciones que el propio documento enumera: el horneado ocurre en el dominio de la textura
  de densidad, no en la geometría, así que excluir una capa según LOD es un parámetro de
  generación de la textura, no una pérdida de granularidad.
- **Hace triviales los números que el documento quiere minimizar**: unos pocos quads por región,
  draw calls por lotes, vértices despreciables. El costo se concentra donde se puede atacar con
  una sola palanca conocida (resolución de render).
- **El movimiento es un offset de UV** por capa: no regenera geometría ni textura (Sección 10).
- **El fade vertical y el fade de LOD son el mismo mecanismo**: un factor de opacidad por slice,
  tal como pide la Sección 7.2.

Qué significa LOD en esta representación (la Sección 3.1 exige declararlo):

| Rango | Slices por capa | Resolución de textura | Capas |
|---|---|---|---|
| 0–300 | 8 | alta | 3 separadas |
| 300–800 | 4 | media | 3 combinadas |
| 800–1500 | 2 | baja | combinación única |
| 1500+ | 1 quad | mínima | combinación única, fade a cero |

(Valores iniciales orientativos, como los de la Sección 7 del documento: se ajustan con datos.)

### Alternativas descartadas

- **Malla extruida de heightmap** (tipo vanilla fancy, con silueta procedural): menos relleno y
  más barata, pero la profundidad hay que fingirla toda en el sombreado y el resultado tiende
  justo al fracaso que la Sección 12 declara inaceptable. Se mantiene como plan B si el costo
  de relleno resulta inaceptable tras medir.
- **Billboards / impostors en todos los rangos**: ordenamiento frágil y popping al girar la
  cámara. Se conservan únicamente como idea para el rango más lejano si el quad único no alcanza.
- **Ray marching volumétrico**: prohibido por la Sección 1, y además el peor candidato posible
  para la meta de costo.

### Riesgo asumido

Es exactamente la tensión que la v1.1 nombra en la Sección 12: los slices son lo que compra la
profundidad y lo que dispara el fill rate. La mitigación es el render a resolución reducida, y
si aun así no se llega al criterio de aceptación, lo que cede es el número de slices — es decir,
la ambición visual — no el presupuesto.

---

## 5. Transparencia, profundidad y composición (Sección 11.1)

- **Mezcla**: alfa premultiplicado. Evita halos oscuros al escalar el target de resolución
  reducida, que es precisamente la operación central de esta arquitectura.
- **Profundidad**: los slices no escriben profundidad. Sí la **leen**, para quedar ocultos
  detrás del terreno.
- **Orden**: dentro de una región, los slices se dibujan de atrás hacia adelante respecto a la
  cámara (el orden se invierte según la cámara esté por encima o por debajo de la capa). Las
  regiones se ordenan por distancia. Como todas las superficies son horizontales y paralelas, el
  orden es calculable exactamente, sin heurísticas.
- **Composición**: el target de nubes se compone con un upsample consciente de profundidad, para
  que los bordes contra el terreno no queden escalonados.
- **Punto de inserción**: después de los bloques translúcidos y antes del clima. A verificar
  contra el comportamiento real del pipeline, incluido el modo de gráficos "fabuloso".
- **Cámara dentro de una nube**: los slices que caen demasiado cerca del plano cercano se
  atenúan progresivamente, más un término de niebla local proporcional a la densidad muestreada
  en la posición de la cámara. Es un requisito visual, no solo una entrada del culling.

---

## 6. Generación, caché y concurrencia

**Flujo de datos:** `seed + región + capa + tiempo → ruido (coords locales) → campo de densidad
→ textura de densidad combinada por LOD → N quads instanciados → target de nubes → composición`.

**Floating origin (Sección 4):** el ruido se evalúa siempre en coordenadas locales a la región.
La posición de las regiones se mantiene en doble precisión en CPU; los vértices se emiten
relativos al origen local. Un cambio de origen es una actualización de transform y **no**
invalida ninguna textura de densidad, porque esas texturas nunca dependieron de coordenadas
absolutas. Este es un beneficio lateral de la decisión de la Sección 4: elimina el rebase caro.

**Concurrencia (Sección 9.4):** pool de hilos de trabajo genera los campos de densidad y arma
los buffers; la subida a GPU ocurre únicamente en el hilo de render. El presupuesto por frame de
la Sección 8.2 limita cuántos resultados se **consumen y suben** por frame, no cuánto se calcula.
Estado por región en doble buffer para que un resultado en vuelo nunca se lea a medio escribir.

**Caché y pooling (Sección 5.2):** mapa `región → estado`, con las texturas de densidad tomadas
de un pool de tamaño fijo por nivel de LOD (todas las texturas de un mismo nivel comparten
dimensiones, así que el pooling es trivial y no fragmenta). Una región que sale de rango libera
su textura al pool, no al recolector de basura.

**Cola de prioridad (Sección 8.1):** las prioridades del documento se respetan tal cual. La
generación de una región es interrumpible entre capas, no atómica.

---

## 7. Seed y multijugador (Sección 4)

**Decisión propuesta:** seed derivada de un identificador estable disponible en cliente
(identificador de mundo o de servidor, más la dimensión), configurable por el jugador, con valor
por defecto fijo. **La coherencia entre jugadores no es un requisito de esta versión** y queda
dicho explícitamente para que no se trate como un bug más adelante. Sincronizar la seed desde el
servidor implicaría un componente de servidor, que está fuera del alcance declarado.

---

## 8. Configuración y respeto a los ajustes del juego (Sección 13.2)

- Config de Forge, con distancia (multiplicador sobre el render distance), calidad/LOD,
  cobertura, velocidad por capa, resolución del target y un interruptor para volver a vanilla.
- Si el ajuste de nubes de Minecraft está en **OFF**, Atmosia no renderiza nada. En modo rápido,
  perfil equivalente de menor costo (menos slices, textura más baja).
- Sin nubes en Nether ni End. En dimensiones de otros mods, seguir la configuración de efectos de
  la propia dimensión en vez de forzar nubes.

---

## 9. Compatibilidad (Sección 13.1)

| Caso | Postura propuesta |
|---|---|
| Iris / Oculus con shader pack activo | Atmosia se desactiva y cede el cielo, registrándolo una vez en el log. Competir por el cielo con un pack es una fuente de errores irreproducibles. |
| Embeddium / Rubidium y similares | Deben poder coexistir: Atmosia no toca el render de chunks. El punto a verificar es el target propio y el modo fabuloso. |
| Distant Horizons | Altera la distancia efectiva del mundo. Decidir explícitamente qué valor se lee: el render distance de vanilla, no el extendido, salvo configuración expresa. |
| Otros mods de cielo / clima / dimensiones | Si ya sustituyeron los efectos de dimensión, Atmosia respeta y se desactiva (ver Ruta A). |

Prior art a revisar antes de la Fase 1: existe al menos un mod de nubes procedurales para Forge
1.20.1 (`simple-clouds`, de nonamecrackers2). Revisar cómo resuelve la supresión de las nubes
vanilla y la convivencia con shader packs ahorraría trabajo de investigación y es, además, un
caso de incompatibilidad directa a contemplar.

---

## 10. Verificaciones pendientes (requieren entorno con Minecraft y Forge)

Cada ítem indica qué abrir, qué buscar y qué decisión dispara el resultado.

1. **Ruta sin mixin.** Abrir el método de `LevelRenderer` que dibuja el mundo y localizar la
   llamada al render de nubes. Determinar de qué condiciones depende (ajuste de nubes del
   jugador, altura de nubes de los efectos de dimensión, valor inválido/NaN).
   → Si una altura inválida suprime el render: **Ruta A**, sin mixin. Si no: **Ruta C**.
2. **Sustitución de efectos de dimensión.** Confirmar cómo se registra o sustituye la entrada de
   efectos del Overworld en 1.20.1 y si Forge ofrece un punto de extensión para ello.
   → Define si la Ruta A es implementable de forma limpia o solo pisando un mapa global.
3. **Etapa de dibujo.** Confirmar en qué etapa de `RenderLevelStageEvent` conviene dibujar y
   componer, y cómo se comporta con el modo de gráficos fabuloso.
4. **Render target propio.** Verificar que se puede crear y componer un target a resolución
   reducida dentro del pipeline de 1.20.1 sin romper el modo fabuloso ni los mods de
   optimización. **Es el supuesto del que depende toda la Sección 4 de este documento**: si cae,
   hay que revisar el número de slices o pasar al plan B (malla extruida).
5. **Versión exacta de Forge** y mappings, para fijar en `gradle.properties`.
6. **Compute shaders** (Sección 9.3): dejar constancia de viabilidad en el entorno objetivo,
   solo como nota para después de la Fase 6. No condiciona nada de lo anterior.

---

## 11. Criterio de aceptación y plan de medición (Secciones 16.1 y 16.2)

El número no puede fijarse antes de medir la línea base, así que el orden propuesto es:

1. Construir el harness de benchmark **antes** que cualquier código de Atmosia: seed fija, hora
   fija, trayectoria de cámara determinista, duración fija con descarte de calentamiento, salida
   a CSV, VSync y límite de FPS desactivados.
2. Medir las nubes **vanilla** con ese harness, en los nueve escenarios de la Sección 16.
3. Recién entonces ratificar el criterio de aceptación de la Sección 16.1 en la forma
   "no más de N ms de frame time añadido a 1080p, con render distance 12, en el hardware de
   referencia declarado".

Valor de partida sugerido, a ratificar con la línea base en la mano: **1.5 ms** de frame time
añadido. Se propone como ancla para discutir, no como dato.

Este harness es reutilizable en todas las fases y es el trabajo de menor riesgo y mayor retorno
del proyecto, por eso se propone moverlo al principio.

---

## 12. Riesgos, ordenados por impacto

| Riesgo | Impacto | Mitigación |
|---|---|---|
| El target a resolución reducida no es viable en el pipeline de 1.20.1 | Alto — cae la mitigación principal de fill rate | Verificación 4 antes de la Fase 1; plan B de malla extruida |
| La supresión de vanilla exige mixin | Alto — es el riesgo principal declarado en el documento | Verificaciones 1 y 2; mixin quirúrgico y documentado si no hay alternativa |
| Fill rate por encima del presupuesto aun a media resolución | Medio | Bajar slices por nivel de LOD; es el parámetro que cede primero |
| Conflicto con shader packs | Medio | Desactivación automática, decidida de antemano |
| Costo de memoria de las texturas de densidad | Medio | Pool por nivel de LOD con dimensiones fijas; medir VRAM desde la Fase 2 |
| La calidad visual de la Fase 5 invalida decisiones de las Fases 2–4 | Medio | Prototipo visual desechable en la Fase 1 (Sección 15) |

---

## 13. Estado

Fase 0 entregada como **propuesta**. No se ha escrito código, ni de producción ni de scaffolding.

Según la regla de oro del documento, hace falta **confirmación explícita** de estas decisiones
—especialmente la de la Sección 4 de este documento, que condiciona todo lo demás— antes de
pasar a la Fase 1. Las verificaciones de la Sección 10 requieren un entorno con Minecraft y
Forge disponibles, que este entorno no tiene.
