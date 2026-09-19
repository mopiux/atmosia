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
 * Construye la geometría de una región a partir de su campo de densidad.
 *
 * Acá se materializa la representación elegida en la Fase 0: por cada slice horizontal se emite un
 * cuádruple por celda cuya densidad supera el umbral de ese slice. Como el umbral es alto en los
 * extremos y bajo en el medio, solo el núcleo de una formación llega arriba y abajo, y el conjunto
 * se lee como un volumen redondeado en vez de como láminas apiladas.
 *
 * Corre en el hilo de render, no en los hilos de trabajo: toca BufferBuilder y OpenGL. Lo que se
 * paraleliza es el cálculo de densidad, que es la parte cara y la que no toca el contexto gráfico.
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
            // Cielo despejado en esta región. Se cachea igual, para no recalcularla cada frame.
            return new RegionMesh(job.key(), lod, null, 0, frame);
        }

        DensityField field = new DensityField(noise, layer);
        int cells = result.cellsPerSide();
        int slices = lod.slices();
        float cellSize = lod.cellSize();
        // El alfa por corte sale de cuántos cortes hay, para que la pila llegue siempre a la misma
        // opacidad y el nivel de detalle no cambie el brillo de la nube al cruzar un umbral.
        float sliceAlpha = DensityField.sliceAlpha(slices);

        BufferBuilder builder = new BufferBuilder(Math.max(256, result.estimatedQuads() * 4 * 16));
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int quads = 0;
        for (int slice = 0; slice < slices; slice++) {
            double threshold = field.sliceThreshold(slice, slices);
            float y = (float) field.sliceHeight(slice, slices);

            for (int cz = 0; cz < cells; cz++) {
                for (int cx = 0; cx < cells; cx++) {
                    float density = result.density()[cz * cells + cx];
                    float alpha = field.cellAlpha(density, threshold);
                    if (alpha <= 0.0F) {
                        continue;
                    }

                    float shade = field.shade(density, slice, slices);
                    float r = layer.red() * shade;
                    float g = layer.green() * shade;
                    float b = layer.blue() * shade;
                    float a = alpha * sliceAlpha;

                    // Coordenadas locales a la región: nunca absolutas del mundo (Sección 4).
                    float x0 = cx * cellSize;
                    float z0 = cz * cellSize;
                    float x1 = x0 + cellSize;
                    float z1 = z0 + cellSize;

                    builder.vertex(x0, y, z0).color(r, g, b, a).endVertex();
                    builder.vertex(x0, y, z1).color(r, g, b, a).endVertex();
                    builder.vertex(x1, y, z1).color(r, g, b, a).endVertex();
                    builder.vertex(x1, y, z0).color(r, g, b, a).endVertex();
                    quads++;
                }
            }
        }

        BufferBuilder.RenderedBuffer rendered = builder.end();
        if (quads == 0) {
            rendered.release();
            return new RegionMesh(job.key(), lod, null, 0, frame);
        }

        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(rendered);
        VertexBuffer.unbind();

        return new RegionMesh(job.key(), lod, buffer, quads, frame);
    }

    /** Cota superior de cuádruples de una región completa, para el presupuesto. */
    public static int worstCaseQuads(LodLevel lod) {
        return lod.maxQuadsPerRegionLayer(RegionKey.REGION_SIZE);
    }
}
