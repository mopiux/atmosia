# Atmosia

Sistema de nubes procedurales para Minecraft Forge 1.20.1. Reemplaza por completo el renderer
de nubes vanilla por uno propio, procedural y optimizado. Sin sistema meteorológico todavía:
esta primera etapa es una base de renderizado, no un simulador de clima.

## Estado actual

**Fase 0 — entregada como propuesta, esperando confirmación. Todavía no hay código.**

El documento de diseño exige que cada fase se presente y se apruebe antes de pasar a la
siguiente. La Fase 0 es investigación y diseño, y termina esperando el visto bueno explícito
sobre la arquitectura propuesta antes de escribir nada.

## Documentos

| Archivo | Qué es |
|---|---|
| `docs/Atmosia_Documento_de_Diseno_v1.1.docx` | La especificación del proyecto. Es la versión vigente: hay que leer esta. |
| `docs/Atmosia_Documento_de_Diseno_v1.0.docx` | El original, conservado solo como referencia histórica. |
| `docs/fase-0-analisis-y-arquitectura.md` | La respuesta a lo que pide el documento: arquitectura propuesta, decisiones con sus alternativas, riesgos y verificaciones pendientes. |

## Qué hace falta para seguir

1. **Aprobar (o discutir) la decisión de arquitectura central** de la Fase 0: la representación
   geométrica. De ella cuelgan LOD, caché, culling y transparencia.
2. **Un entorno con Minecraft y Forge disponibles.** Las verificaciones de la Sección 10 del
   entregable de Fase 0 requieren leer el código real de 1.20.1, y compilar y ejecutar el juego.
   No se pudieron hacer donde se redactó ese documento.

## Principio del proyecto

> "La complejidad visual no debe convertirse automáticamente en complejidad geométrica."
