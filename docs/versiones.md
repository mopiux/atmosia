# Versiones de Atmosia

*Qué versiones existen, qué cambió en cada una y cómo bajar cualquiera.*

---

## La lista

| Versión | Commit | Rama para descargar | Qué es |
|---|---|---|---|
| **0.2.1** | `6a62e2d` | `version-0.2.1` | La actual. Igual que la 0.2.0 en el renderer, más el arreglo del CSV y el documento del sistema de nubes. |
| **0.2.0** | `7f02ed0` | `version-0.2.0` | La primera que compila con el menú. **La que produjo los benchmarks del 18/09 a las 16:40**, los primeros con vanilla apagado de verdad. |
| **0.0.1** | `6525c11` | `version-0.0.1` | El renderer original, con las tres correcciones de la primera prueba real. La que se probó en las dos primeras tandas. |

**No existe una 0.1.0.** El número se salteó al pasar de la 0.0.1 a la 0.2.0, para que el salto se notara de un vistazo en la lista de mods del juego.

## Cómo bajar una versión

```
https://github.com/mopiux/atmosia/archive/refs/heads/version-0.0.1.zip
https://github.com/mopiux/atmosia/archive/refs/heads/version-0.2.0.zip
https://github.com/mopiux/atmosia/archive/refs/heads/version-0.2.1.zip
```

Cada ZIP trae el proyecto completo en ese punto exacto. Se compila igual que cualquier otro: ver `docs/Atmosia_Pasos_Para_Probarlo.docx`.

---

## Qué cambió en cada una

### 0.2.1 — actual

Sin cambios en el renderer. Las nubes se ven y rinden igual que en la 0.2.0.

- **El `results.csv` del benchmark deja de corromperse en silencio.** Si el encabezado del archivo existente no coincide con el actual, el viejo se aparta como `results-anterior-<fecha>.csv` en vez de agregar filas corridas debajo de un encabezado que ya no describe las columnas. El error pasó de verdad: la tanda del 18/09 quedó corrida tres columnas.
- **`docs/sistema-de-nubes.md`**: el sistema de generación explicado entero, con sus ventajas, sus costos y las mediciones interpretadas.

### 0.2.0

Primera versión con menú de configuración funcionando.

- **Menú del mod**, en la lista de mods → Atmosia → Configuración:
  - Modo de nubes: `Atmosia` / `Vanilla` / `Ninguna`
  - Perfil gráfico: `Bajo` / `Medio` / `Alto` / `Personalizado`
  - Cantidad de nubes: 20% a 200%
  - Panel de estado con el ajuste de nubes real del juego
- **La supresión de vanilla se reaplica una vez por tick.** Aplicarla una sola vez, como hacía la 0.0.1, no alcanzaba: el menú de opciones la reescribe al cerrarse, una recarga del archivo la revierte y cualquier otro mod que toque el ajuste gana por ser el último.
- **`coverageScale` por fin llega a la densidad.** Estaba declarado en la configuración desde el principio y no lo leía nadie: el control de cantidad de nubes existía y no hacía absolutamente nada.
- **Los perfiles no agrandan la celda.** Fijado con test: una celda grande se lee como un rectángulo en el cielo por lejos que esté, y eso es un defecto visual, no un ajuste de calidad.
- El CSV del benchmark gana `cloud_mode`, `quality_profile` y `coverage_scale` — que es lo que destapó el error corregido en 0.2.1.

### 0.0.1

Primera versión con renderer completo, incluidas las tres correcciones que salieron de la primera prueba real:

- **El temporizador de GPU se colgaba.** Si el anillo de consultas se llenaba no se abría una nueva, y como la cosecha solo ocurría con una consulta abierta, el anillo no se vaciaba nunca más. En `fast_travel` repitió el mismo valor durante 1263 frames seguidos, el 72% de la corrida.
- **Celdas de LOD demasiado grandes** (64 y 128 bloques), que se veían como sábanas rectangulares cruzando el cielo.
- **Domo de nubes demasiado corto**, cuyo borde caía dentro del campo de visión.

**Limitación conocida de esta versión:** la supresión de nubes vanilla no funcionaba de forma confiable. Se aplicaba una sola vez y el juego la revertía. Corregido en 0.2.0.

---

## Sobre los tags

Lo correcto sería que cada versión fuera un **tag** de git, no una rama: un tag es inmutable y aparece en la sección Releases de GitHub, una rama puede moverse.

No se pudieron crear desde la sesión donde se hizo este trabajo: el push de `refs/tags` devuelve 403 por los permisos del entorno, mientras que el de ramas funciona. Las ramas `version-*` son el sustituto, y cumplen lo mismo para descargar.

Quien tenga el repositorio clonado puede crear los tags de verdad con:

```bash
git tag -a v0.0.1 6525c11 -m "Atmosia 0.0.1"
git tag -a v0.2.0 7f02ed0 -m "Atmosia 0.2.0"
git tag -a v0.2.1 6a62e2d -m "Atmosia 0.2.1"
git push origin v0.0.1 v0.2.0 v0.2.1
```

Una vez creados, las ramas `version-*` se pueden borrar y los ZIP pasan a bajarse de `.../archive/refs/tags/v0.0.1.zip`.

---

## Una aclaración importante sobre los `.jar`

**En el repositorio no hay ningún `.jar`, de ninguna versión.** Nunca se construyó uno en el entorno donde se escribió el mod, porque no tiene acceso a los servidores de Minecraft ni de Forge.

Un número de versión acá es una línea de texto en `gradle.properties`, no un archivo instalable. El único `.jar` que existe es el que genera `gradlew build` en `build\libs\` de quien compila.

**Si querés conservar builds instalables, hay que guardar ese `.jar` a mano**, renombrado con su versión. El repositorio conserva el código; los ejecutables, no.
