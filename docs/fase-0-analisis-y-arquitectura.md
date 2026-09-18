# Atmosia — Fase 0: análisis, arquitectura propuesta y verificaciones pendientes

Estado: **aprobada el 2026-09-18, con una enmienda.** La arquitectura, el modelo de
transparencia, la concurrencia, la seed, la compatibilidad y la configuración quedan aprobados
tal como están. La enmienda afecta a las Verificaciones 1 y 4 —las dos de impacto alto— que
deben incluir la revisión de cómo resuelve lo mismo el mod `simple-clouds` antes de decidir
entre Ruta A/C y entre target reducido/malla extruida. Esa revisión está registrada en la
Sección 9.1, con una restricción de licencia importante que la limita.

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
| simple-clouds | Conflicto duro: ambos reemplazan las nubes vanilla. Detectar y desactivarse. Ver Sección 9.1, incluida la restricción de licencia. |

### 9.1 Prior art revisado: simple-clouds

`simple-clouds` (nonamecrackers2) es un mod de nubes procedurales para Forge 1.20.1, publicado
en Modrinth y CurseForge, actualmente en beta abierta. Se revisó su documentación pública.

**Restricción de licencia — leer antes de seguir.** Está publicado bajo *PolyForm Perimeter
License 1.0.1*, que es una licencia con cláusula de no competencia explícita: "cualquier
propósito es un propósito permitido, excepto proveer a otros un producto que compita con el
software", y aclara que un producto compite "aunque se provea de forma gratuita" y aunque esté
"portado a otro lenguaje de programación". Atmosia es, por definición, un sustituto funcional de
simple-clouds. En consecuencia:

- **No se puede tomar, copiar ni derivar código de simple-clouds para Atmosia.** Ni una función,
  ni un shader, ni una estructura de clases.
- Leer su código fuente para decidir cómo implementar Atmosia es un riesgo de contaminación que
  no vale la pena correr: los mismos hechos sobre el render de nubes en 1.20.1 se obtienen del
  código de Minecraft y de la documentación de Forge, que es la fuente definitiva de todas
  formas. Por eso esta revisión se limitó a la documentación pública del mod.

**Lo que su documentación pública sí aporta, y es mucho:**

1. **Los compute shaders son viables en Forge 1.20.1.** simple-clouds genera la geometría de las
   nubes en GPU con compute shaders, iterando una grilla de vóxeles contra capas de ruido 3D.
   Esto es evidencia de campo contra la duda de la Sección 9.3 del documento de diseño: no es un
   camino teórico. Sigue sin ser necesario para Atmosia, pero deja de ser una incógnita.
2. **Una cuarta representación geométrica existe y funciona**: malla generada en GPU a partir de
   un campo de vóxeles. No estaba entre los candidatos de la Sección 3.1. Es más costosa en
   geometría y más barata en relleno que la ruta de slices, o sea que ataca la tensión por el
   otro extremo. Se incorpora como alternativa consciente, no elegida.
3. **Su autor reconoce impacto notable en frames, sobre todo en GPUs viejas.** Un mod de nubes
   procedurales bien hecho y con la geometría en GPU igualmente se paga caro. Es un argumento a
   favor del criterio de aceptación numérico de la Sección 11 de este documento: la meta de
   "costo parecido a vanilla" es ambiciosa y hay que medirla, no asumirla.
4. **Corrobora la decisión de seed de la Sección 7.** simple-clouds enfrentó exactamente el mismo
   problema y lo resolvió igual: en modo solo-cliente la seed es aleatoria por sesión (o fija por
   configuración del jugador) y no hay coherencia entre jugadores; la sincronización real
   requiere un componente de servidor. Nuestra decisión —cliente, sin coherencia garantizada,
   seed configurable— queda validada por un caso real.

**Lo que no aporta y sigue abierto:** cómo suprime el render de nubes vanilla. Eso requeriría
leer su código, que es justamente lo que la licencia desaconseja. Se obtiene mejor de la fuente
directa (Verificación 1).

**Como caso de compatibilidad, es un conflicto duro:** dos mods que reemplazan las nubes vanilla
no pueden coexistir. Atmosia debe detectar simple-clouds y desactivarse, registrándolo en el log,
igual que con los shader packs.

---

## 10. Verificaciones pendientes (requieren entorno con Minecraft y Forge)

Cada ítem indica qué abrir, qué buscar y qué decisión dispara el resultado.

1. **Ruta sin mixin.** Abrir el método de `LevelRenderer` que dibuja el mundo y localizar la
   llamada al render de nubes. Determinar de qué condiciones depende (ajuste de nubes del
   jugador, altura de nubes de los efectos de dimensión, valor inválido/NaN).
   → Si una altura inválida suprime el render: **Ruta A**, sin mixin. Si no: **Ruta C**.
   *Enmienda de aprobación:* debía revisarse antes cómo lo resuelve simple-clouds. Esa revisión
   se hizo hasta donde la licencia lo permite (Sección 9.1) y **no cubre este punto**: determinar
   su mecanismo exige leer su código, y hacerlo contamina un proyecto que compite con él. La
   decisión Ruta A/C se toma contra el código de Minecraft y Forge, que además es la fuente
   autoritativa. Si el autor del proyecto prefiere revisarlo igualmente, es una decisión suya y
   debe quedar registrada aquí junto con su motivo.
2. **Sustitución de efectos de dimensión.** Confirmar cómo se registra o sustituye la entrada de
   efectos del Overworld en 1.20.1 y si Forge ofrece un punto de extensión para ello.
   → Define si la Ruta A es implementable de forma limpia o solo pisando un mapa global.
3. **Etapa de dibujo.** Confirmar en qué etapa de `RenderLevelStageEvent` conviene dibujar y
   componer, y cómo se comporta con el modo de gráficos fabuloso.
4. **Render target propio.** Verificar que se puede crear y componer un target a resolución
   reducida dentro del pipeline de 1.20.1 sin romper el modo fabuloso ni los mods de
   optimización. **Es el supuesto del que depende toda la Sección 4 de este documento**: si cae,
   hay que revisar el número de slices o pasar al plan B (malla extruida).
   *Enmienda de aprobación:* la revisión de simple-clouds (Sección 9.1) aporta aquí un dato
   concreto: existe una tercera salida verificada en producción —generar la malla en GPU con
   compute shaders— que evita el problema del target reducido por completo, a cambio de mover el
   costo de relleno a costo de geometría y de depender de compute shaders. Queda como plan C
   explícito si el target reducido resulta inviable y la malla extruida no alcanza visualmente.
   No se adopta ahora: implicaría reescribir la Sección 4 entera y depender de una característica
   que el documento de diseño mantiene fuera de la base jugable.
5. **Versión exacta de Forge** y mappings, para fijar en `gradle.properties`.
6. **Compute shaders** (Sección 9.3): la revisión de la Sección 9.1 muestra que hay un mod de
   nubes en producción para Forge 1.20.1 que los usa, así que la viabilidad deja de ser una
   incógnita abierta. Queda por confirmar en el hardware y los drivers de referencia, y sigue
   siendo trabajo posterior a la Fase 6: la base jugable de Atmosia no depende de ellos.

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
| Contaminación de licencia por mirar código de simple-clouds | Medio, y legal en vez de técnico | No leer su fuente; obtener los mismos hechos del código de Minecraft y de la documentación de Forge (Sección 9.1) |

---

## 13. Estado

**Fase 0 aprobada el 2026-09-18**, con la enmienda registrada en la cabecera y desarrollada en
las Verificaciones 1 y 4. La arquitectura de la Sección 4, el modelo de transparencia, la
concurrencia, la seed, la compatibilidad y la configuración quedan firmes y son la base de la
Fase 1.

Sigue sin escribirse código. Lo que bloquea el arranque de la Fase 1 no es una decisión sino el
entorno: las Verificaciones 1 a 5 requieren Minecraft y Forge disponibles para leer el código
real, compilar y ejecutar, y el entorno donde se redactó este documento no los tiene.

El primer trabajo de la Fase 1 que **no** depende de esas verificaciones es el harness de
benchmark de la Sección 11, que además hay que correr contra las nubes vanilla antes de escribir
una línea de Atmosia, para tener la línea base contra la cual ratificar el criterio de
aceptación.
