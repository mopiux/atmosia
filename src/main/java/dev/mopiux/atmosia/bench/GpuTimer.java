package dev.mopiux.atmosia.bench;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Tiempo de GPU por frame mediante consultas de temporizacion de OpenGL.
 *
 * El documento de diseno es explicito en esto (Seccion 16.2): si el tiempo de GPU no se mide, hay
 * que registrar que no se midio, nunca estimarlo. Por eso {@link #isAvailable()} existe y por eso
 * el CSV distingue vacio de cero.
 *
 * Las consultas son asincronas: el resultado de un frame no esta listo hasta varios frames
 * despues. Leerlo de inmediato sincronizaria CPU y GPU y arruinaria justamente lo que se quiere
 * medir, asi que se usa un anillo de consultas y se cosecha la mas vieja cuando ya esta lista.
 *
 * SIN VERIFICAR: nada de esto se pudo ejecutar. Los nombres de LWJGL son los esperables para
 * OpenGL 3.3, pero hay que confirmarlos contra la version de LWJGL que trae 1.20.1.
 */
public final class GpuTimer {

    private static final int RING_SIZE = 4;

    private final int[] queries = new int[RING_SIZE];
    private final boolean[] pending = new boolean[RING_SIZE];
    private final boolean available;

    private int writeIndex;
    private int readIndex;
    private boolean queryOpen;
    private double lastResultMs = Double.NaN;

    public GpuTimer() {
        this.available = GL.getCapabilities() != null && GL.getCapabilities().OpenGL33;
        if (this.available) {
            for (int i = 0; i < RING_SIZE; i++) {
                this.queries[i] = GL33.glGenQueries();
            }
        }
    }

    public boolean isAvailable() {
        return this.available;
    }

    /** Abre la consulta del frame actual. Sin efecto si el anillo esta lleno. */
    public void beginFrame() {
        if (!this.available || this.queryOpen || this.pending[this.writeIndex]) {
            return;
        }
        GL33.glBeginQuery(GL33.GL_TIME_ELAPSED, this.queries[this.writeIndex]);
        this.queryOpen = true;
    }

    /**
     * Cierra la consulta del frame actual y cosecha la mas vieja que ya este disponible.
     *
     * La cosecha ocurre siempre, haya o no consulta abierta. Cuando no era asi, un anillo lleno
     * dejaba el temporizador colgado para siempre: no se abria consulta, y sin consulta abierta
     * tampoco se cosechaba, asi que el anillo nunca se vaciaba. La primera medicion real lo
     * delato - el mismo valor de GPU repetido durante 1263 frames seguidos.
     */
    public void endFrame() {
        if (!this.available) {
            return;
        }
        if (this.queryOpen) {
            GL33.glEndQuery(GL33.GL_TIME_ELAPSED);
            this.queryOpen = false;
            this.pending[this.writeIndex] = true;
            this.writeIndex = (this.writeIndex + 1) % RING_SIZE;
        }
        this.harvest();
    }

    /**
     * Ultimo tiempo de GPU cosechado, en milisegundos, o NaN si todavia no hay ninguno.
     * Corresponde a un frame anterior, no al actual: es el precio de no sincronizar.
     */
    public double lastResultMillis() {
        return this.lastResultMs;
    }

    private void harvest() {
        if (!this.pending[this.readIndex]) {
            return;
        }
        int query = this.queries[this.readIndex];
        if (GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_TRUE) {
            long nanos = GL33.glGetQueryObjecti64(query, GL15.GL_QUERY_RESULT);
            this.lastResultMs = nanos / 1_000_000.0D;
            this.pending[this.readIndex] = false;
            this.readIndex = (this.readIndex + 1) % RING_SIZE;
        }
    }

    public void dispose() {
        if (!this.available) {
            return;
        }
        if (this.queryOpen) {
            GL33.glEndQuery(GL33.GL_TIME_ELAPSED);
            this.queryOpen = false;
        }
        for (int query : this.queries) {
            GL33.glDeleteQueries(query);
        }
    }
}
