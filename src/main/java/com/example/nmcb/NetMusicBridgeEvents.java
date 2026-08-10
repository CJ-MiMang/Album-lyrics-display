package com.example.nmcb;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;

/**
 * 服务端事件处理器
 * 在注册表注册阶段将 DisplaySource 注册到 Create 的注册表，并关联到 NetMusic 的 Music Player 方块实体类型
 */
@Mod.EventBusSubscriber(modid = NetMusicCreateBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class NetMusicBridgeEvents {
    // NBT 键名，用于在方块实体 PersistentData 中存储 LRC 歌词
    public static final String LRC_NBT_KEY = "NMCB_LrcContent";
    // NBT 键名，歌曲总时长（秒）
    public static final String SONG_TIME_NBT_KEY = "NMCB_SongTimeSeconds";

    // DisplaySource 实例
    private static NetMusicLyricDisplaySource displaySource;

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        // 在 Create 的 DisplaySource 注册表可用时注册
        event.register(CreateBuiltInRegistries.DISPLAY_SOURCE.key(), helper -> {
            // 创建 DisplaySource 实例
            displaySource = new NetMusicLyricDisplaySource();
            ResourceLocation sourceId = new ResourceLocation(NetMusicCreateBridge.MOD_ID, "netmusic_lyric");
            helper.register(sourceId, displaySource);

            // 查找 NetMusic 的 Music Player 方块实体类型并关联
            ResourceLocation musicPlayerId = new ResourceLocation("netmusic", "music_player");
            if (BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(musicPlayerId)) {
                BlockEntityType<?> musicPlayerType = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(musicPlayerId);
                if (musicPlayerType != null) {
                    DisplaySource.BY_BLOCK_ENTITY.add(musicPlayerType, displaySource);
                }
            }

            NetMusicCreateBridge.LOGGER.info("[NMCB] Registered NetMusic Lyric DisplaySource");
        });
    }
}
