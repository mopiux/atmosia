package dev.mopiux.atmosia;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Punto de entrada del mod.
 *
 * Todo Atmosia es de cliente: reemplaza el renderizado de nubes y no toca la logica del mundo. En
 * un servidor dedicado no hay nada que hacer.
 */
@Mod(Atmosia.MOD_ID)
public class Atmosia {
    public static final String MOD_ID = "atmosia";

    public Atmosia() {
        // Los enganches se registran con @Mod.EventBusSubscriber(Dist.CLIENT), que ademas
        // garantiza que no se carguen en un servidor dedicado. Registrarlos tambien a mano haria
        // que cada evento llegara dos veces y que cada frame se midiera duplicado.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, AtmosiaConfig.SPEC);
            // El registro del menu se hace desde una clase aparte: nombrar una pantalla aca
            // cargaria clases de cliente en un servidor dedicado, donde no existen.
            dev.mopiux.atmosia.client.gui.AtmosiaConfigGui.register();
        }
    }
}
