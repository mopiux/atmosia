# ATMOSIA — Pasos para probarlo

*Cómo compilar el mod y verlo funcionando*

*Escrito para seguir sin saber de programación*

## Antes de empezar

Lo que vas a hacer es armar el mod desde su código y abrir un Minecraft de prueba con él ya cargado. No hay un archivo listo para instalar: hay que construirlo primero, y eso es lo que hacen estos pasos.

Calculá entre veinte minutos y una hora, casi todo esperando descargas. Necesitás conexión a internet y unos 3 GB libres en disco.

> **Esto no toca tu Minecraft**
>
> El juego de prueba que se abre en el paso 6 es una instalación aparte, separada de tu Minecraft normal. No modifica tus mundos, tus mods ni tu configuración. Si algo sale mal, borrás la carpeta del proyecto y no queda rastro.

> **Es probable que el paso 5 falle, y no es culpa tuya**
>
> El código del mod nunca se pudo compilar mientras se escribía, porque el entorno donde se hizo no tenía acceso a los servidores de Minecraft ni de Forge. Es normal que aparezcan errores la primera vez.
>
> Si pasa, no hay nada que arreglar de tu lado: copiás el error y lo mandás. El paso 9 explica exactamente qué copiar.

## 1. Instalar Java 17

Minecraft 1.20.1 necesita Java 17. Si ya lo tenés, saltá al paso 2.

Descargalo de adoptium.net, que es la distribución gratuita más común. Elegí la versión 17 (aparece como "Temurin 17") para tu sistema operativo e instalá con las opciones por defecto.

Para confirmar que quedó bien instalado, abrí una terminal (el paso 3 explica cómo) y escribí:

```
java -version
```

Tiene que responder algo que empiece con 17.

> **¿Ya tenés Java 21 u otra versión?**
>
> No alcanza, y esto está comprobado, no supuesto: con Java 21 la compilación corta de entrada con el mensaje "Unsupported class file major version 65". Ni siquiera llega a leer el código del mod.
>
> Hay que instalar la 17 igual. Las versiones de Java conviven sin pisarse: instalar la 17 no desinstala la 21 ni rompe nada de lo que ya tengas.
>
> La comprobación opcional del paso 10 sí funciona con Java 21, porque no usa Minecraft.

### Si tenés las dos versiones instaladas

Puede pasar que java -version siga respondiendo 21 después de instalar la 17. En ese caso hay que decirle explícitamente cuál usar. En Windows, cerrá la terminal, abrí una nueva y escribí esto antes de compilar, ajustando la ruta a donde quedó instalada la 17:

```
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17
```

Para saber la ruta exacta, mirá dentro de C:\Program Files\Eclipse Adoptium: va a haber una carpeta que empieza con jdk-17. Ese ajuste dura mientras la terminal esté abierta, así que hay que repetirlo si la cerrás.

## 2. Bajar el proyecto

Entrá a la página del proyecto:

```
https://github.com/mopiux/atmosia
```

Botón verde "Code", y después "Download ZIP". Descomprimí el archivo en un lugar que puedas encontrar después: el Escritorio está bien.

Vas a terminar con una carpeta llamada atmosia-main o parecida. Esa es la carpeta del proyecto y todo lo que sigue pasa adentro de ella.

## 3. Abrir la terminal en esa carpeta

La terminal es una ventana donde se escriben comandos en vez de hacer clic. Cómo abrirla directamente en la carpeta del proyecto:

| Sistema | Cómo |
|---|---|
| `Windows` | Entrá a la carpeta, hacé clic derecho en un espacio vacío y elegí "Abrir en Terminal". Si no aparece, escribí cmd en la barra de direcciones de la carpeta y apretá Enter. |
| `Mac` | Clic derecho sobre la carpeta, "Servicios", "Nuevo terminal en la carpeta". |
| `Linux` | Clic derecho dentro de la carpeta, "Abrir en una terminal". |

Para confirmar que estás en el lugar correcto, escribí dir en Windows o ls en Mac y Linux. Tenés que ver archivos llamados build.gradle y gradle.properties. Si no los ves, estás en la carpeta equivocada: probablemente haya otra carpeta adentro.

## 4. Preparar el permiso de ejecución (solo Mac y Linux)

En Windows saltá este paso. En Mac y Linux, escribí:

```
chmod +x gradlew
```

No responde nada, y eso está bien: quiere decir que funcionó.

## 5. Compilar el mod

Este es el paso largo. Escribí el comando que corresponda a tu sistema:

| Sistema | Comando |
|---|---|
| `Windows` | gradlew.bat build |
| `Mac y Linux` | ./gradlew build |

La primera vez descarga Minecraft, Forge y las herramientas de construcción: son varios minutos y van a aparecer muchas líneas de texto. Es normal. Dejalo trabajar aunque parezca trabado.

Si termina con BUILD SUCCESSFUL, salió bien y podés seguir. Si termina con BUILD FAILED, andá directo al paso 9.

## 6. Abrir el juego de prueba

Con la compilación exitosa, escribí:

| Sistema | Comando |
|---|---|
| `Windows` | gradlew.bat runClient |
| `Mac y Linux` | ./gradlew runClient |

Se abre un Minecraft con el mod ya cargado. La primera vez también tarda. No te pide cuenta ni contraseña: es un modo de desarrollo.

## 7. Crear un mundo para mirar

1. Un jugador, Crear mundo nuevo.
2. Modo de juego: Creativo.
3. En "Más opciones" o en la pantalla de creación, activar Trucos.
4. Crear el mundo y esperar a que cargue.
5. Volar hacia arriba con doble salto y después la barra espaciadora, para ver bien el cielo.

## 8. Qué mirar

Lo importante es lo visual. En orden de gravedad, de lo más grave a lo más fino:

- Que no haya dos tipos de nubes a la vez. Si ves las nubes cuadradas de siempre por debajo de las nuevas, algo falló y es lo primero a reportar.
- Que haya nubes. Si el cielo quedó completamente vacío, también es un problema.
- Que no parezcan tres planos transparentes separados: donde las capas se cruzan debería verse una masa más densa.
- Que al alejarte no aparezcan agujeros ni cambios bruscos.
- Que al subir por encima de las nubes se desvanezcan de a poco en vez de cortarse de golpe.
- Que se muevan, y que las capas se muevan a velocidades distintas.

Sacá capturas de pantalla de lo que veas, esté bien o mal. Una imagen dice más que cualquier descripción, y nadie vio todavía cómo se ve esto.

## 9. Si algo falla

Es el escenario esperable y no requiere que entiendas el error. Lo que hace falta es el texto completo:

- Seleccioná todo el texto de la terminal desde donde escribiste el comando hasta el final.
- Copialo y pegalo en un archivo de texto, o mandalo tal cual.
- Si el juego llegó a abrir y después se cerró solo, buscá también la carpeta run, adentro otra llamada logs, y mandá el archivo latest.log.
- Agregá qué sistema operativo usás y qué versión de Java tenés (lo que respondió el java -version del paso 1).

Con eso alcanza para diagnosticarlo. No hace falta que interpretes nada.

## 10. Errores conocidos y qué significan

| Mensaje | Qué pasa |
|---|---|
| `gradlew.bat no se reconoce como un comando` | Faltan los archivos del wrapper en la carpeta. Volvé a descargar el ZIP del proyecto: faltaban en las primeras versiones y ya están agregados. |
| `Unsupported class file major version 65` | Estás usando Java 21. Instalá la 17 y mirá el paso 1. |
| `Unsupported class file major version 61 o similar` | Lo mismo pero al revés: la versión de Java es más vieja de lo esperado. |
| `Could not resolve net.minecraftforge` | Problema de red, o la versión de Forge en gradle.properties no existe. Confirmala en files.minecraftforge.net para 1.20.1. |
| `BUILD FAILED con errores que mencionan archivos .java` | Es lo esperable: errores del código del mod. Copiá el texto y mandalo, no hay nada que puedas hacer de tu lado. |

## 11. Opcional: una comprobación sin Minecraft

Si querés verificar algo sin esperar las descargas, hay una parte del mod que se puede probar sola: la matemática que genera las formas de las nubes. Desde la misma terminal, en la carpeta del proyecto, escribí estas dos líneas, una y después la otra:

```
javac --release 17 -d prueba src/main/java/dev/mopiux/atmosia/core/*.java src/test/java/dev/mopiux/atmosia/core/CoreSmokeTest.java
```

```
java -cp prueba dev.mopiux.atmosia.core.CoreSmokeTest
```

Tendría que imprimir 66 líneas terminadas en OK y cerrar con TODO OK. No dibuja nada, pero confirma que la parte de abajo funciona. Esta sí está probada de antemano, así que si falla es una señal rara y vale la pena avisarlo.
