package com.example.nmcb;

import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：将 NetMusic 唱片机的 LRC 歌词发送到服务端存储
 *
 * 注意：不发送 currentTime，因为客户端线程读取的 currentTime 是初始值（songTime*20+64），
 * 只有服务端线程才会递减它。服务端直接从自己的方块实体读取 currentTime。
 */
public class LyricToServerMessage {
    private final BlockPos pos;
    private final String lrcContent;
    private final int songTimeSeconds;

    public LyricToServerMessage(BlockPos pos, String lrcContent, int songTimeSeconds) {
        this.pos = pos;
        this.lrcContent = lrcContent;
        this.songTimeSeconds = songTimeSeconds;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(lrcContent);
        buf.writeVarInt(songTimeSeconds);
    }

    public static LyricToServerMessage decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String lrcContent = buf.readUtf();
        int songTimeSeconds = buf.readVarInt();
        return new LyricToServerMessage(pos, lrcContent, songTimeSeconds);
    }

    public static void handle(LyricToServerMessage message, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            if (player.level().getBlockEntity(message.pos) instanceof TileEntityMusicPlayer te) {
                if (message.lrcContent.isEmpty() && message.songTimeSeconds == 0) {
                    // 播放停止，清除歌词数据
                    te.getPersistentData().remove(NetMusicBridgeEvents.LRC_NBT_KEY);
                    te.getPersistentData().remove(NetMusicBridgeEvents.SONG_TIME_NBT_KEY);
                    te.setChanged();
                    NetMusicCreateBridge.LOGGER.info("[NMCB] Cleared lyrics for music player at {}", message.pos);
                } else {
                    te.getPersistentData().putString(NetMusicBridgeEvents.LRC_NBT_KEY, message.lrcContent);
                    te.getPersistentData().putInt(NetMusicBridgeEvents.SONG_TIME_NBT_KEY, message.songTimeSeconds);
                    te.setChanged();
                    NetMusicCreateBridge.LOGGER.info("[NMCB] Stored lyrics at {} ({} chars, songTime={}s, serverCT={})",
                            message.pos, message.lrcContent.length(), message.songTimeSeconds, te.getCurrentTime());
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
