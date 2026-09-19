package dev.mopiux.atmosia.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.mopiux.atmosia.core.CloudLayerDef;
import dev.mopiux.atmosia.core.DensityField;
import dev.mopiux.atmosia.core.LodLevel;
import dev.mopiux.atmosia.core.NoiseField;
import dev.mopiux.atmosia.core.RegionKey;

/**
 * Construye la geometria de una region a partir de su campo de densidad.
 *
 * Aca se materializa la representacion elegida en la Fase 0: por cada slice horizontal se emite un
 * cuadruple por celda cuya densidad supera el umbral de ese slice. Como el umbral es alto en los
 * extremos y bajo en el medio, solo el nucleo de una formacion llega arriba y abajo, y el conjunto
 * se lee como un volumen redondeado en vez de como laminas apiladas.
 *
 * Corre en el hilo de render, no en los hilos de trabajo: toca BufferBuilder y OpenGL. Lo que se
 * paraleliza es el calculo de densidad, que es la parte cara y la que no toca el contexto grafico.
 *
 * SIN VERIFICAR contra el juego real.
 */
public final class RegionMeshBuilder {

    private RegionMeshBuilder() {
    }

    public static RegionMesh build(DensityJob.Result result, NoiseField noise, long frame) {
        DensityJob job = result.job();
        CloudLayerDef layer = job.layer();
        LodLevel lod = job.lod();

        if (result.isEmpty()) {
            // Cielo despejado en esta region. Se cachea igual, para no recalcularla cada frame.
            return new RegionMesh(job.key(), lod, null, 0, frame, job.topDown());
        }

        DensityField field = new DensityField(noise, layer);
        int cells = result.cellsPerSide();
        int corners = result.cornersPerSide();
        int slices = lod.slices();
        float cellSize = lod.cellSize();
        // Un alfa por corte, no uno solo para todos: los cortes de los extremos pesan menos, de
        // modo que la capa se desvanece hacia arriba y hacia abajo en vez de terminar en un canto.
        // La escalera esta normalizada para que la pila entera siga llegando a la misma opacidad.
        float[] sliceAlphas = DensityField.sliceAlphas(slices);

        BufferBuilder builder = new BufferBuilder(Math.max(256, result.estimatedQuads() * 4 * 16));
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float[] d = result.density();
        int quads = 0;
        for (int paso = 0; paso < slices; paso++) {
            // El orden de emision ES el orden de mezcla, y tiene que ser de lejos a cerca.
            //
            // Cada region es un draw call propio, asi que la region entera se mezcla de una vez.
            // Un rayo rasante cerca del borde entre dos regiones cruza algunos cortes de una y
            // algunos de la otra; si el orden interno de cada region no coincide con el orden de
            // profundidad global, el reparto cambia de golpe al cruzar el borde y aparece una
            // linea recta de 256 bloques. Medido, ese salto llegaba a 10 niveles de gris.
            //
            // Mirando desde abajo, los cortes altos estan mas lejos, asi que van primero. Desde
            // arriba es al reves. Como las regiones ya se dibujan de lejos a cerca, con el orden
            // interno correcto el borde queda exacto.
            int slice = job.topDown() ? slices - 1 - paso : paso;
            double threshold = field.sliceThreshold(slice, slices);
            float y = (float) field.sliceHeight(slice, slices);
            float sliceAlpha = sliceAlphas[slice];

            for (int cz = 0; cz < cells; cz++) {
                for (int cx = 0; cx < cells; cx++) {
                    // Las cuatro esquinas de la celda, en el orden en que se emiten los vertices.
                    float d00 = d[cz * corners + cx];
                    float d01 = d[(cz + 1) * corners + cx];
                    float d11 = d[(cz + 1) * corners + cx + 1];
                    float d10 = d[cz * corners + cx + 1];

                    float a00 = field.cellAlpha(d00, threshold);
                    float a01 = field.cellAlpha(d01, threshold);
                    float a11 = field.cellAlpha(d11, threshold);
                    float a10 = field.cellAlpha(d10, threshold);
                    if (a00 <= 0.0F && a01 <= 0.0F && a11 <= 0.0F && a10 <= 0.0F) {
                        continue;
                    }

                    // Coordenadas locales a la region: nunca absolutas del mundo (Seccion 4).
                    float x0 = cx * cellSize;
                    float z0 = cz * cellSize;
                    float x1 = x0 + cellSize;
                    float z1 = z0 + cellSize;

                    emit(builder, layer, field, x0, y, z0, d00, a00 * sliceAlpha, slice, slices);
                    emit(builder, layer, field, x0, y, z1, d01, a01 * sliceAlpha, slice, slices);
                    emit(builder, layer, field, x1, y, z1, d11, a11 * sliceAlpha, slice, slices);
                    emit(builder, layer, field, x1, y, z0, d10, a10 * sliceAlpha, slice, slices);
                    quads++;
                }
            }
        }

        BufferBuilder.RenderedBuffer rendered = builder.end();
        if (quads == 0) {
            rendered.release();
            return new RegionMesh(job.key(), lod, null, 0, frame, job.topDown());
        }

        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(rendered);
        VertexBuffer.unbind();

        return new RegionMesh(job.key(), lod, buffer, quads, frame, job.topDown());
    }

    /**
     * Un vertice, con su color y su alfa propios.
     *
     * El sombreado tambien se calcula por esquina y no por celda: con un valor por celda el
     * cuadrilatero queda de un color plano y la grilla se ve aunque el alfa se interpole.
     */
    private static void emit(BufferBuilder builder, CloudLayerDef layer, DensityField field,
                             float x, float y, float z, float density, float alpha,
                             int slice, int slices) {
        float shade = field.shade(density, slice, slices);
        builder.vertex(x, y, z)
                .color(layer.red() * shade, layer.green() * shade, layer.blue() * shade, alpha)
                .endVertex();
    }

    /** Cota superior de cuadruples de una region completa, para el presupuesto. */
    public static int worstCaseQuads(LodLevel lod) {
        return lod.maxQuadsPerRegionLayer(RegionKey.REGION_SIZE);
    }
}
