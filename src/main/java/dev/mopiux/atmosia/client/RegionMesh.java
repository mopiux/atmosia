package dev.mopiux.atmosia.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.mopiux.atmosia.core.LodLevel;
import dev.mopiux.atmosia.core.RegionKey;
import javax.annotation.Nullable;

/**
 * Geometria subida a GPU de una region y capa, con su estado de cache (Seccion 5.2).
 *
 * El campo de densidad no se guarda despues de construir la malla: se puede recalcular en
 * cualquier momento a partir de la seed, y guardarlo solo gastaria memoria. Lo que si se conserva
 * es el buffer, porque reconstruirlo es lo caro.
 */
public final class RegionMesh {

    private final RegionKey key;
    private final LodLevel lod;
    @Nullable
    private VertexBuffer buffer;
    private final int quads;
    private long lastUsedFrame;

    public RegionMesh(RegionKey key, LodLevel lod, @Nullable VertexBuffer buffer, int quads, long frame) {
        this.key = key;
        this.lod = lod;
        this.buffer = buffer;
        this.quads = quads;
        this.lastUsedFrame = frame;
    }

    public RegionKey key() {
        return this.key;
    }

    public LodLevel lod() {
        return this.lod;
    }

    @Nullable
    public VertexBuffer buffer() {
        return this.buffer;
    }

    public int quads() {
        return this.quads;
    }

    /** Una region puede quedar vacia: cielo despejado en esa zona. Se cachea igual. */
    public boolean isEmpty() {
        return this.buffer == null || this.quads == 0;
    }

    public long lastUsedFrame() {
        return this.lastUsedFrame;
    }

    public void markUsed(long frame) {
        this.lastUsedFrame = frame;
    }

    /** Bytes aproximados en GPU: POSITION_COLOR son 16 por vertice, cuatro por cuadruple. */
    public long approximateGpuBytes() {
        return (long) this.quads * 4L * 16L;
    }

    /** Libera el buffer. Solo puede llamarse desde el hilo de render. */
    public void close() {
        if (this.buffer != null) {
            this.buffer.close();
            this.buffer = null;
        }
    }
}
