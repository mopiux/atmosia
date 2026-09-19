# Atmosia

Sistema de nubes procedurales para Minecraft Forge 1.20.1. Reemplaza por completo el renderer
de nubes vanilla por uno propio, procedural y optimizado. Sin sistema meteorológico todavía:
esta primera etapa es una base de renderizado, no un simulador de clima.

## Estado actual

**Código completo del renderer, sin compilar nunca.**

La Fase 0 fue aprobada y las fases siguientes están escritas: supresión de las nubes vanilla,
generación procedural, caché con presupuesto, LOD, culling con fade, multi-capa y sombreado.

El asterisco importa: **nada de lo que toca la API de Minecraft se compiló jamás**, porque el
entorno donde se escribió no puede descargar Forge ni Mojang. El núcleo procedural —ruido,
densidad, LOD, fade, prioridad, presupuesto— sí está compilado y probado, porque no depende del
juego. Ver `docs/fases.md` para el detalle de qué está verificado y qué no.

Lo que falta es un entorno con Minecraft: compilar, corregir lo que salte, y medir.

## Documentos

| Archivo | Qué es |
|---|---|
| `docs/Atmosia_Documento_de_Diseno_v1.1.docx` | La especificación del proyecto. Es la versión vigente: hay que leer esta. |
| `docs/Atmosia_Documento_de_Diseno_v1.0.docx` | El original, conservado solo como referencia histórica. |
| `docs/sistema-de-nubes.md` | **Cómo funciona el sistema de nubes que está andando hoy**, del ruido al dibujo, con sus ventajas y sus costos medidos. |
| `docs/artefactos-visuales.md` | Las bandas grises y la "recarga" de nubes: diagnóstico medido y corrección. |
| `docs/propuesta-0.3.0-nubes-horneadas.md` | Propuesta de rediseño: reemplazar la geometría por celda por texturas horneadas. Con sus dos costos nuevos y sus criterios de abandono. |
| `docs/fase-0-analisis-y-arquitectura.md` | La respuesta a lo que pide el documento: arquitectura propuesta, decisiones con sus alternativas, riesgos y verificaciones pendientes. |
| `docs/Atmosia_Pasos_Para_Probarlo.docx` (y `.md`) | **Si nunca compilaste nada, empezá por acá.** Paso a paso, desde instalar Java. |
| `docs/guia-de-prueba.md` (y su versión `.docx`) | La guía técnica: qué decidir, cómo leer el CSV, qué reportar. |
| `docs/benchmark.md` | Cómo funciona el harness de medición, qué mide y qué no. |
| `docs/fases.md` | Qué cubre cada fase del documento de diseño y en qué estado de verificación está. |

## Qué hace falta para seguir

1. **Compilar**: `./gradlew build`. La versión de Forge ya está puesta (47.4.10); si Gradle no la
   encuentra, corregirla en `gradle.properties`.
2. **Corregir lo que salte.** Los puntos donde la API de 1.20.1 hay que confirmarla están
   marcados con `VERIFICAR` en el código.
3. **Medir contra vanilla** con el harness, y recién entonces fijar el criterio de aceptación.

## Principio del proyecto

> "La complejidad visual no debe convertirse automáticamente en complejidad geométrica."
