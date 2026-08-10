package com.example.nmcb;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;

/**
 * NetMusic Create Bridge - 桥接 NetMusic 唱片机歌词与 Create Display Link
 *
 * 让 Display Link 能读取 NetMusic Music Player 的歌词，显示在翻牌显示器上。
 */
@Mod(NetMusicCreateBridge.MOD_ID)
public class NetMusicCreateBridge {
    public static final String MOD_ID = "ald";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String NETWORK_VERSION = "1.0.0";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            id("main"),
            () -> NETWORK_VERSION,
            NETWORK_VERSION::equals,
            NETWORK_VERSION::equals
    );

    public NetMusicCreateBridge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CHANNEL.registerMessage(0, LyricToServerMessage.class,
                    LyricToServerMessage::encode,
                    LyricToServerMessage::decode,
                    LyricToServerMessage::handle);
            LOGGER.info("[NMCB] Network packets registered");
        });
        LOGGER.info("[NMCB] NetMusic Create Bridge initialized");
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
