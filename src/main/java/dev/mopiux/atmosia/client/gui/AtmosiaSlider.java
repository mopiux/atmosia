package dev.mopiux.atmosia.client.gui;

import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * Deslizador de un valor continuo, con confirmacion diferida.
 *
 * El detalle que importa es cuando se confirma. Un deslizador emite un valor nuevo por cada pixel
 * que se arrastra, y confirmar cada uno significaria, aca, escribir el archivo de configuracion y
 * tirar toda la geometria del cielo docenas de veces por segundo. Asi que mientras se arrastra solo
 * se actualiza la etiqueta, y el valor se confirma al soltar. El jugador ve el numero moverse en
 * vivo y el mod trabaja una sola vez.
 */
public final class AtmosiaSlider extends AbstractSliderButton {

    private final String label;
    private final double min;
    private final double max;
    private final DoubleConsumer onCommit;
    private final LabelFormatter formatter;

    /** Lo ultimo que se confirmo, para no confirmar de nuevo un valor que no se movio. */
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
        // A proposito no confirma: solo la etiqueta, que updateMessage ya actualizo.
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
        this.commit();
    }

    /**
     * Confirma el valor si cambio. Lo llama el soltar del raton y tambien el cierre de la pantalla,
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
