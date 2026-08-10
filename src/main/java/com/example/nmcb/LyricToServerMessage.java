package com.example.nmcb;

import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：将 NetMusic 唱片机的 LRC 歌词发送到服务端存储
 */
public record LyricToServerMessage(BlockPos pos, String lrcContent, int songTimeSeconds,
                                    int currentTicks) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<LyricToServerMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(NetMusicCreateBridge.MOD_ID, "lyric_to_server"));

    public static final StreamCodec<ByteBuf, LyricToServerMessage> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, LyricToServerMessage::pos,
            ByteBufCodecs.STRING_UTF8, LyricToServerMessage::lrcContent,
            ByteBufCodecs.VAR_INT, LyricToServerMessage::songTimeSeconds,
            ByteBufCodecs.VAR_INT, LyricToServerMessage::currentTicks,
            LyricToServerMessage::new
    );

    public static void handle(LyricToServerMessage message, IPayloadContext context) {
        if (!context.flow().isServerbound()) return;

        context.enqueueWork(() -> {
            if (context.player() == null) return;
            if (context.player().level().getBlockEntity(message.pos) instanceof TileEntityMusicPlayer te) {
                te.getPersistentData().putString(NetMusicBridgeEvents.LRC_NBT_KEY, message.lrcContent);
                te.getPersistentData().putInt(NetMusicBridgeEvents.SONG_TIME_NBT_KEY, message.songTimeSeconds);
                // 存储客户端发送 LRC 时服务端的 currentTime 值，用于精确计算已播放时间
                te.getPersistentData().putInt(NetMusicBridgeEvents.INITIAL_CURRENT_TIME_NBT_KEY, message.currentTicks);
                te.markDirty();
                NetMusicCreateBridge.LOGGER.debug("[NMCB] Stored lyrics for music player at {} (songTime={}s, currentTime={})",
                        message.pos, message.songTimeSeconds, message.currentTicks);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
