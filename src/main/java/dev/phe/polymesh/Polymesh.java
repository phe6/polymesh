package dev.phe.polymesh;

import dev.phe.polymesh.client.PolymeshClientEvents;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Polymesh.MOD_ID)
public class Polymesh {
    public static final String MOD_ID = "polymesh";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public Polymesh() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            PolymeshClientEvents.register(modEventBus);
        }
        LOGGER.info("Polymesh library loaded.");
    }
}
