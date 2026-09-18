# ATMOSIA — Guía de prueba

*Cómo compilar lo que hay, medir la línea base y qué falta decidir*

*Para quien tenga entorno de desarrollo de Minecraft Forge 1.20.1*

## 0. Qué es esto y qué todavía no es

Este repositorio todavía no contiene un mod que dibuje nubes. Ni una línea del renderer propio está escrita: el documento de diseño exige que cada fase se apruebe antes de pasar a la siguiente, y el prototipo que reemplaza las nubes vanilla es la Fase 1, que no se empezó.

Lo que sí hay es el trabajo previo completo: el documento de diseño revisado, el análisis y la arquitectura de la Fase 0, y una herramienta de medición lista para usar.

> **Lo más importante que tenés que saber antes de abrir nada**
>
> Nada de este código se compiló nunca. El entorno donde se escribió no tenía acceso a los servidores de Forge ni de Mojang, así que no pudo descargarse el MDK ni ejecutarse una sola vez.
>
> Es esperable que la primera compilación falle en algo. Los puntos donde la API de 1.20.1 hay que confirmarla están marcados con VERIFICAR en el código, y la versión de Forge quedó deliberadamente como REEMPLAZAR en vez de un número puesto de memoria.

Tu trabajo en esta ronda son tres cosas: hacer que compile, medir las nubes vanilla, y decidir los tres puntos de la última sección.

## 1. Qué hay en el repositorio

https://github.com/mopiux/atmosia

| Archivo | Qué es |
|---|---|
| `docs/Atmosia_Documento_de_Diseno_v1.1.docx` | Tu documento con una revisión técnica integrada. Es la versión vigente. |
| `docs/Atmosia_Documento_de_Diseno_v1.0.docx` | El original, solo como referencia histórica. |
| `docs/fase-0-analisis-y-arquitectura.md` | La Fase 0: arquitectura propuesta, alternativas descartadas, riesgos y verificaciones. |
| `docs/benchmark.md` | Cómo funciona el harness de medición y qué mide. |
| `src/main/java/.../bench/` | El harness. Mide tiempos sin saber qué está dibujando, así que sirve para medir vanilla. |
| `src/test/java/.../BenchSmokeTest.java` | Comprobaciones de la lógica pura. Esto sí está probado y pasa. |

## 2. Requisitos

- JDK 17. Con 21 instalado alcanza si Gradle puede apuntar a un toolchain 17.
- Conexión a internet: la primera compilación descarga el MDK de Forge y tarda bastante.
- Minecraft Java Edition para poder jugar el cliente de desarrollo.

## 3. Pasos

1. Clonar el repositorio: git clone https://github.com/mopiux/atmosia
2. Abrir gradle.properties y reemplazar forge_version=REEMPLAZAR por la versión exacta de Forge para 1.20.1 (la recomendada de files.minecraftforge.net, rama 47.x). Este es el único valor que hay que completar a mano.
3. Compilar: ./gradlew build (en Windows, gradlew.bat build). Si falla, guardá el error tal cual: es información útil, no un problema tuyo.
4. Levantar el cliente de desarrollo: ./gradlew runClient
5. Crear un mundo nuevo en creativo, con trampas activadas. Anotá si es mundo normal o superplano, porque cambia lo que se renderiza y por lo tanto los números.
6. Verificar que el mod cargó: escribir /atmosiabench list en el chat. Debería listar nueve escenarios. Si el comando no existe, el mod no cargó y eso es lo primero a resolver.
7. Correr el primer escenario: /atmosiabench run below_clouds. El jugador pasa a espectador, se fija la hora y el clima, y la cámara se mueve sola. Son 5 segundos de calentamiento más 30 de medición. No toques nada mientras corre.
8. Repetir con los nueve escenarios: clear_sky, full_coverage, camera_still, camera_spin, fast_travel, altitude_sweep, below_clouds, inside_clouds, above_clouds.
9. Idealmente tres corridas de cada uno, para poder quedarse con la mediana. Una sola corrida no dice nada.
10. Buscar los resultados en run/atmosia-benchmarks/results.csv

### Comprobación opcional, sin Gradle

La parte del harness que no depende de Minecraft se puede compilar y probar sola, sin descargar nada. El comando exacto está en la cabecera de BenchSmokeTest.java. Si eso pasa, la estadística y el formato del CSV están bien y cualquier problema está del lado de la integración con el juego.

## 4. Cómo leer el CSV

Una fila por corrida en results.csv, más un volcado frame a frame por corrida en un archivo aparte. Las columnas que más importan:

- avg_fps y low_1pct_fps: el promedio y el 1% de frames más lentos. El segundo es el que delata los tirones; si están muy separados, hay microstuttering aunque el promedio se vea bien.
- cpu_frame_avg_ms: es la métrica sobre la que se define el criterio de aceptación.
- gpu_frame_avg_ms: puede venir vacía si el driver no soporta consultas de temporización.
- Las columnas de regiones, vértices y memoria van a venir vacías: son del renderer propio, que todavía no existe.

> **Convención del CSV**
>
> Una celda vacía significa "no medido", nunca cero. La diferencia importa cuando se comparen fases más adelante, así que conviene no rellenar esos huecos a mano.

## 5. Qué devolver

- Los errores de compilación, copiados tal cual.
- El archivo results.csv completo.
- Las specs de la máquina: CPU, GPU, RAM, resolución de pantalla y render distance usado. Sin eso los números no se pueden comparar con nada.
- Si el juego crashea o el comando no aparece, el log de la carpeta run/logs.

## 6. Tres decisiones que necesitan tu visto bueno

Estas son las que no puede tomar nadie más que vos, porque es tu proyecto.

### 6.1 La representación geométrica

La propuesta es: campo de densidad por región, dibujado como slices horizontales alfa-blended, sobre un render target a resolución reducida. De esa decisión cuelgan LOD, caché, culling y transparencia, así que cambiarla después es caro. Está argumentada en la Sección 4 del documento de Fase 0, con las alternativas descartadas y por qué.

De paso resuelve la tensión entre layer baking y LOD por capa que tu documento marcaba sin resolver: al hornear en el dominio de la textura de densidad y no en la geometría, excluir una capa según LOD pasa a ser un parámetro de generación.

### 6.2 El criterio de aceptación

Tu documento pedía un costo parecido al de las nubes vanilla, pero escrito así no se podía verificar: no hay medición que lo apruebe ni lo rechace. La propuesta es fijar un número: no más de 1,5 ms de frame time añadido respecto a vanilla, a 1080p y render distance 12, sobre una máquina de referencia declarada.

Ese 1,5 es un ancla para discutir, no un dato. Se ratifica cuando tengas la línea base de vanilla medida, que es justamente lo que producen los pasos de arriba.

### 6.3 La licencia de simple-clouds

Existe un mod de nubes procedurales para Forge 1.20.1 que hace algo muy parecido: simple-clouds, de nonamecrackers2. Está publicado bajo PolyForm Perimeter License 1.0.1, que tiene una cláusula de no competencia explícita: cualquier propósito está permitido excepto proveer a otros un producto que compita con el software, y aclara que compite aunque se provea gratis.

Atmosia es un sustituto funcional de ese mod, así que no se puede tomar ni derivar su código. Por eso la revisión que se hizo se limitó a su documentación pública. Si no compartís esa lectura, decilo ahora y no después de que alguien haya copiado algo.

Su documentación pública igual aportó cosas útiles: confirma que los compute shaders son viables en Forge 1.20.1, aporta una cuarta representación geométrica posible (malla por vóxeles generada en GPU), y su autor reconoce impacto notable en frames — lo que refuerza que la meta de costo hay que medirla y no asumirla.

## 7. Qué viene después

Con la compilación andando y la línea base medida, sigue la Fase 1: reemplazar las nubes vanilla y renderizar una única capa procedural, sin buscar todavía la calidad visual definitiva.

La verificación más importante que queda pendiente, y que conviene hacer primero, es si se pueden apagar las nubes vanilla sin mixin, a través de la configuración de efectos de dimensión. Si se puede, desaparece de raíz el riesgo principal del proyecto, que según tu propio documento no es de rendimiento sino de compatibilidad con otros mods.
