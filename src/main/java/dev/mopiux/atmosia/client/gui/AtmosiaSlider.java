package dev.mopiux.atmosia.client.gui;

import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * Deslizador de un valor continuo, con confirmación diferida.
 *
 * El detalle que importa es cuándo se confirma. Un deslizador emite un valor nuevo por cada píxel
 * que se arrastra, y confirmar cada uno significaría, acá, escribir el archivo de configuración y
 * tirar toda la geometría del cielo docenas de veces por segundo. Así que mientras se arrastra solo
 * se actualiza la etiqueta, y el valor se confirma al soltar. El jugador ve el número moverse en
 * vivo y el mod trabaja una sola vez.
 */
public final class AtmosiaSlider extends AbstractSliderButton {

    private final String label;
    private final double min;
    private final double max;
    private final DoubleConsumer onCommit;
    private final LabelFormatter formatter;

    /** Lo último que se confirmó, para no confirmar de nuevo un valor que no se movió. */
    private double committed;

    @FunctionalInterface
    public interface LabelFormatter {
        String format(double value);
    }

    public AtmosiaSlider(int x, int y, int width, int height, String label, double min, double max,
                         double initial, LabelFormatter formatter, DoubleConsumer onCommit) {
        super(x, y, width, height, Component.empty(), fraction(initial, min, max));
        this.label = label;
        this.min = min;
        this.max = max;
        this.formatter = formatter;
        this.onCommit = onCommit;
        this.committed = initial;
        this.updateMessage();
    }

    private static double fraction(double value, double min, double max) {
        if (max <= min) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (value - min) / (max - min)));
    }

    /** El valor actual del deslizador, confirmado o no. */
    public double value() {
        return this.min + this.value * (this.max - this.min);
    }

    @Override
    protected void updateMessage() {
        this.setMessage(Component.literal(this.label + ": " + this.formatter.format(this.value())));
    }

    @Override
    protected void applyValue() {
        // A propósito no confirma: solo la etiqueta, que updateMessage ya actualizó.
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
        this.commit();
    }

    /**
     * Confirma el valor si cambió. Lo llama el soltar del ratón y también el cierre de la pantalla,
     * porque las flechas del teclado mueven el deslizador sin que haya un soltar.
     */
    public void commit() {
        double current = this.value();
        if (current == this.committed) {
            return;
        }
        this.committed = current;
        this.onCommit.accept(current);
    }
}
