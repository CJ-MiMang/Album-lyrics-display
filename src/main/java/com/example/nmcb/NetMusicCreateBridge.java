package com.example.nmcb;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
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

    public NetMusicCreateBridge(IEventBus modEventBus) {
        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::registerPackets);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[NMCB] NetMusic Create Bridge initialized");
    }

    private void registerPackets(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION).optional();
        registrar.playToServer(
                LyricToServerMessage.TYPE,
                LyricToServerMessage.STREAM_CODEC,
                LyricToServerMessage::handle
        );
        LOGGER.info("[NMCB] Network packets registered");
    }
}
