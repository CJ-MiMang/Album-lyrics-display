package com.example.nmcb;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 客户端事件处理器
 * 监听 NetMusic 唱片机的播放状态，通过反射读取歌词并发送到服务端
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = NetMusicCreateBridge.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class NetMusicClientEvents {
    // 记录已经同步过歌词的唱片机位置，避免重复发送
    private static final Map<BlockPos, String> syncedPlayers = new HashMap<>();

    // 反射字段缓存
    private static Field lyricRecordField = null;
    private static Field playerInvField = null;
    private static boolean reflectionFailed = false;

    @SubscribeEvent
    public static void onClientTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level == null || !level.isClientSide()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // 搜索玩家附近的唱片机方块实体
        BlockPos playerPos = mc.player.blockPosition();
        int range = 32; // 搜索范围

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (!(be instanceof TileEntityMusicPlayer musicPlayer)) continue;
                    if (!musicPlayer.isPlay()) {
                        // 播放停止，清除同步记录
                        syncedPlayers.remove(pos);
                        continue;
                    }

                    // 尝试获取歌词
                    String lrcContent = buildLrcString(musicPlayer);
                    if (lrcContent == null || lrcContent.isEmpty()) continue;

                    // 检查是否已经同步过
                    String existing = syncedPlayers.get(pos);
                    if (lrcContent.equals(existing)) continue;

                    // 获取歌曲总时长（秒）
                    int songTimeSeconds = getSongTimeSeconds(musicPlayer);

                    // 读取当前 currentTime（倒计时 tick），发送到服务端用于精确计算已播放时间
                    int currentTicks = musicPlayer.getCurrentTime();

                    // 发送歌词到服务端
                    syncedPlayers.put(pos, lrcContent);
                    LyricToServerMessage msg = new LyricToServerMessage(pos, lrcContent, songTimeSeconds, currentTicks);
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(msg);

                    NetMusicCreateBridge.LOGGER.debug("[NMCB] Sent lyrics for music player at {} (songTime={}s, currentTime={})",
                            pos, songTimeSeconds, currentTicks);
                }
            }
        }
    }

    /**
     * 获取歌曲总时长（秒）
     * 通过反射读取 playerInv 中的 CD 物品，获取 SongInfo.songTime
     */
    private static int getSongTimeSeconds(TileEntityMusicPlayer musicPlayer) {
        // 方法1：通过反射读取 playerInv
        try {
            if (playerInvField == null) {
                playerInvField = TileEntityMusicPlayer.class.getDeclaredField("playerInv");
                playerInvField.setAccessible(true);
            }
            Object inv = playerInvField.get(musicPlayer);
            if (inv instanceof net.neoforged.neoforge.items.ItemStackHandler handler) {
                var cdStack = handler.getStackInSlot(0);
                if (!cdStack.isEmpty()) {
                    ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(cdStack);
                    if (songInfo != null) {
                        return songInfo.songTime;
                    }
                }
            }
        } catch (Exception e) {
            NetMusicCreateBridge.LOGGER.debug("[NMCB] Failed to get songTime via reflection", e);
        }

        // 方法2：通过 persistentData 读取 currentTime，然后反推
        // currentTime = songTime*20+64 - playedTicks
        // 但我们不知道 playedTicks，所以这个方法不准确
        // 使用一个合理的默认值
        return 0;
    }

    /**
     * 通过反射读取 TileEntityMusicPlayer.lyricRecord，并转换为 LRC 格式字符串
     */
    private static String buildLrcString(TileEntityMusicPlayer musicPlayer) {
        if (reflectionFailed) return null;

        try {
            if (lyricRecordField == null) {
                lyricRecordField = TileEntityMusicPlayer.class.getDeclaredField("lyricRecord");
                lyricRecordField.setAccessible(true);
            }

            Object obj = lyricRecordField.get(musicPlayer);
            if (!(obj instanceof LyricRecord lyricRecord)) return null;

            Int2ObjectSortedMap<String> lyrics = lyricRecord.getLyrics();
            if (lyrics == null || lyrics.isEmpty()) return null;

            // 构建 LRC 格式字符串
            StringJoiner lrcJoiner = new StringJoiner("\n");
            for (var entry : lyrics.int2ObjectEntrySet()) {
                int tick = entry.getIntKey();
                String text = entry.getValue();
                if (text == null || text.isEmpty()) continue;

                // tick 转换为 mm:ss.xx 格式 (1 tick = 50ms)
                int totalMs = tick * 50;
                int minutes = totalMs / 60000;
                int seconds = (totalMs % 60000) / 1000;
                int ms = (totalMs % 1000) / 10;

                String timeTag = String.format("[%02d:%02d.%02d]", minutes, seconds, ms);
                lrcJoiner.add(timeTag + text);
            }

            // 也包含翻译歌词（如果有）
            Int2ObjectSortedMap<String> transLyrics = lyricRecord.getTransLyrics();
            if (transLyrics != null && !transLyrics.isEmpty()) {
                lrcJoiner.add(""); // 空行分隔
                for (var entry : transLyrics.int2ObjectEntrySet()) {
                    int tick = entry.getIntKey();
                    String text = entry.getValue();
                    if (text == null || text.isEmpty()) continue;

                    int totalMs = tick * 50;
                    int minutes = totalMs / 60000;
                    int seconds = (totalMs % 60000) / 1000;
                    int ms = (totalMs % 1000) / 10;

                    String timeTag = String.format("[%02d:%02d.%02d]", minutes, seconds, ms);
                    lrcJoiner.add(timeTag + text);
                }
            }

            return lrcJoiner.toString();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            NetMusicCreateBridge.LOGGER.error("[NMCB] Failed to access lyricRecord via reflection", e);
            reflectionFailed = true;
            return null;
        }
    }
}
