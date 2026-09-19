package dev.mopiux.atmosia.client.gui;

import dev.mopiux.atmosia.AtmosiaConfig;
import dev.mopiux.atmosia.client.AtmosiaClient;
import dev.mopiux.atmosia.client.CloudRenderer;
import dev.mopiux.atmosia.client.VanillaCloudSuppressor;
import dev.mopiux.atmosia.core.CloudMode;
import dev.mopiux.atmosia.core.QualityProfile;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Menu de configuracion de Atmosia, el que abre el boton "Configuracion" de la lista de mods.
 *
 * Tiene tres controles y un panel de estado, y el panel no es decorativo: la primera prueba real del
 * mod termino en una discusion que nadie podia zanjar -si las nubes que se veian eran las vanilla o
 * las nuestras- porque la unica forma de saberlo era leer el log. Aca el ajuste de nubes del juego
 * esta a la vista, y el modo "Ninguna" deja el cielo completamente vacio: si con ese modo queda
 * alguna nube, es vanilla y la supresion fallo. Eso es una respuesta, no una impresion.
 */
public final class AtmosiaConfigScreen extends Screen {

    private static final int ROW_WIDTH = 220;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_SPACING = 34;

    private static final int COLOR_LABEL = 0xFFFFFF;
    private static final int COLOR_HINT = 0xA0A0A0;
    private static final int COLOR_WARN = 0xFFAA00;
    private static final int COLOR_VALUE = 0xE0E0E0;

    /** Rango del multiplicador de cobertura, el mismo que acepta la configuracion. */
    private static final double COVERAGE_MIN = 0.2D;
    private static final double COVERAGE_MAX = 2.0D;

    @Nullable
    private final Screen parent;

    @Nullable
    private AtmosiaSlider coverageSlider;

    private CloudMode mode;
    private QualityProfile profile;

    public AtmosiaConfigScreen(@Nullable Screen parent) {
        super(Component.literal("Atmosia"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.mode = AtmosiaConfig.CLIENT.cloudMode.get();
        this.profile = AtmosiaConfig.CLIENT.qualityProfile.get();

        int x = this.width / 2 - ROW_WIDTH / 2;
        int y = 34;

        this.addRenderableWidget(CycleButton.<CloudMode>builder(m -> Component.literal(m.displayName()))
                .withValues(CloudMode.values())
                .withInitialValue(this.mode)
                .create(x, y, ROW_WIDTH, ROW_HEIGHT, Component.literal("Nubes"),
                        (button, value) -> {
                            this.mode = value;
                            AtmosiaConfig.CLIENT.cloudMode.set(value);
                        }));
        y += ROW_SPACING;

        this.addRenderableWidget(CycleButton.<QualityProfile>builder(p -> Component.literal(p.displayName()))
                .withValues(QualityProfile.values())
                .withInitialValue(this.profile)
                .create(x, y, ROW_WIDTH, ROW_HEIGHT, Component.literal("Calidad"),
                        (button, value) -> {
                            this.profile = value;
                            AtmosiaConfig.CLIENT.qualityProfile.set(value);
                        }));
        y += ROW_SPACING;

        this.coverageSlider = new AtmosiaSlider(x, y, ROW_WIDTH, ROW_HEIGHT,
                "Cantidad de nubes",
                COVERAGE_MIN, COVERAGE_MAX,
                AtmosiaConfig.CLIENT.coverageScale.get(),
                AtmosiaConfigScreen::formatCoverage,
                value -> AtmosiaConfig.CLIENT.coverageScale.set(round(value)));
        this.addRenderableWidget(this.coverageSlider);

        this.addRenderableWidget(Button.builder(Component.literal("Restablecer valores por defecto"),
                        button -> this.resetToDefaults())
                .bounds(x, this.height - 52, ROW_WIDTH, ROW_HEIGHT)
                .build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(x, this.height - 28, ROW_WIDTH, ROW_HEIGHT)
                .build());
    }

    /**
     * La cantidad se muestra en porcentaje y no como multiplicador: "130%" se entiende sin saber
     * que es la cobertura de una capa, y "1,3x" no.
     */
    private static String formatCoverage(double value) {
        return String.format(Locale.ROOT, "%d%%", Math.round(value * 100.0D));
    }

    /** Dos decimales: el archivo de configuracion no necesita el ruido del punto flotante. */
    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private void resetToDefaults() {
        AtmosiaConfig.CLIENT.cloudMode.set(CloudMode.ATMOSIA);
        AtmosiaConfig.CLIENT.qualityProfile.set(QualityProfile.MEDIUM);
        AtmosiaConfig.CLIENT.coverageScale.set(1.0D);
        // Se rearma la pantalla para que los controles muestren los valores nuevos.
        this.rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, COLOR_LABEL);

        int x = this.width / 2 - ROW_WIDTH / 2;
        int y = 34;

        graphics.drawString(this.font, this.mode.description(), x, y + ROW_HEIGHT + 4, COLOR_HINT, false);
        y += ROW_SPACING;
        graphics.drawString(this.font, this.profile.description(), x, y + ROW_HEIGHT + 4, COLOR_HINT, false);
        y += ROW_SPACING;
        graphics.drawString(this.font, "Que parte del cielo tapan. Al cambiarla, el cielo se rehace.",
                x, y + ROW_HEIGHT + 4, COLOR_HINT, false);
        y += ROW_SPACING;

        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderStatus(graphics, x, y + 6);
    }

    /** Panel de estado: lo que esta pasando de verdad, no lo que la configuracion pide. */
    private void renderStatus(GuiGraphics graphics, int x, int y) {
        graphics.drawString(this.font, Component.literal("Estado"), x, y, COLOR_LABEL, false);
        y += 14;

        String standDown = AtmosiaClient.standDownReason();
        if (standDown != null) {
            graphics.drawString(this.font,
                    Component.literal("Atmosia dejo el cielo a otro mod: " + standDown),
                    x, y, COLOR_WARN, false);
            return;
        }

        boolean suppressed = VanillaCloudSuppressor.isActive();
        String gameSetting = suppressed
                ? "apagadas por Atmosia"
                : VanillaCloudSuppressor.currentGameSetting().name().toLowerCase(Locale.ROOT);
        graphics.drawString(this.font, Component.literal("Nubes del juego: " + gameSetting),
                x, y, COLOR_VALUE, false);
        y += 11;

        CloudRenderer renderer = AtmosiaClient.renderer();
        String drawing = renderer == null
                ? "Atmosia no esta dibujando"
                : "Atmosia dibujando - " + renderer.activeRegions() + " regiones en memoria";
        graphics.drawString(this.font, Component.literal(drawing), x, y, COLOR_VALUE, false);
        y += 11;

        int reapplied = VanillaCloudSuppressor.reapplyCount();
        if (reapplied > 0) {
            graphics.drawString(this.font,
                    Component.literal("Algo volvio a encender las nubes del juego " + reapplied
                            + " veces; Atmosia las volvio a apagar."),
                    x, y, COLOR_WARN, false);
            y += 11;
        }

        if (this.mode == CloudMode.NONE) {
            graphics.drawString(this.font,
                    Component.literal("Si con este modo ves nubes, son vanilla y la supresion fallo."),
                    x, y, COLOR_HINT, false);
        }
    }

    @Override
    public void onClose() {
        // Las flechas del teclado mueven el deslizador sin que haya un soltar del raton, asi que el
        // ultimo valor podria no estar confirmado todavia.
        if (this.coverageSlider != null) {
            this.coverageSlider.commit();
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        // Sin pausa: el mundo sigue corriendo detras del menu, atenuado pero visible, asi que un
        // cambio de cantidad o de perfil se ve sin tener que salir y volver a entrar.
        return false;
    }
}
