package dev.mopiux.atmosia.client.gui;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Engancha el menu de Atmosia al boton "Configuracion" de la lista de mods.
 *
 * Vive en su propia clase y no en el punto de entrada del mod porque nombrar una pantalla desde
 * codigo comun cargaria clases de cliente en un servidor dedicado, donde no existen.
 */
public final class AtmosiaConfigGui {

    private AtmosiaConfigGui() {
    }

    /** Se llama durante la construccion del mod, solo en cliente. */
    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new AtmosiaConfigScreen(parent)));
    }
}
