package com.example.nmcb;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemStackHandler;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 客户端事件处理器
 * 监听 NetMusic 唱片机的播放状态，通过反射读取歌词并发送到服务端
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = NetMusicCreateBridge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class NetMusicClientEvents {
    // 记录已同步歌词的内容（按位置），避免重复发送相同内容
    private static final Map<BlockPos, String> syncedLyrics = new HashMap<>();
    // 记录已报告为"正在播放"的位置，用于在播放停止时清理
    private static final Map<BlockPos, Boolean> knownPlaying = new HashMap<>();

    // 反射字段缓存
    private static Field lyricRecordField = null;
    private static Field playerInvField = null;
    private static boolean reflectionFailed = false;

    // 每 10 tick 强制重新同步一次（即使内容没变），确保服务器数据最新
    private static int tickCounter = 0;
    private static final int FORCE_RESYNC_INTERVAL = 10;

    @SubscribeEvent
    public static void onClientTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Level level = event.level;
        if (level == null || !level.isClientSide()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        tickCounter++;

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
                        if (knownPlaying.remove(pos) != null) {
                            syncedLyrics.remove(pos);
                            // 通知服务器播放停止（清除歌词数据）
                            LyricToServerMessage msg = new LyricToServerMessage(pos, "", 0);
                            NetMusicCreateBridge.CHANNEL.sendToServer(msg);
                        }
                        continue;
                    }

                    // 标记为正在播放
                    knownPlaying.put(pos, true);

                    // 尝试获取歌词
                    String lrcContent = buildLrcString(musicPlayer);

                    // 获取歌曲总时长（秒）
                    int songTimeSeconds = getSongTimeSeconds(musicPlayer);

                    // 检查是否需要发送：
                    // 1. 歌词内容发生变化
                    // 2. 每 FORCE_RESYNC_INTERVAL tick 强制同步（确保服务器 currentTime 更新）
                    // 3. 歌词从 null 变为有内容
                    String lastSynced = syncedLyrics.get(pos);
                    boolean contentChanged = lrcContent != null && !lrcContent.equals(lastSynced);
                    boolean forceSync = (tickCounter % FORCE_RESYNC_INTERVAL == 0);
                    boolean firstSyncWithLyrics = lrcContent != null && lastSynced == null;

                    if (contentChanged || forceSync || firstSyncWithLyrics) {
                        // 如果歌词为 null（尚未加载），使用空字符串表示"正在播放但歌词未就绪"
                        String toSend = (lrcContent != null) ? lrcContent : "";

                        syncedLyrics.put(pos, lrcContent); // 存储实际值（可能为 null）
                        LyricToServerMessage msg = new LyricToServerMessage(pos, toSend, songTimeSeconds);
                        NetMusicCreateBridge.CHANNEL.sendToServer(msg);

                        if (lrcContent != null) {
                            NetMusicCreateBridge.LOGGER.info("[NMCB] Synced lyrics for music player at {} ({} chars, songTime={}s)",
                                    pos, lrcContent.length(), songTimeSeconds);
                        }
                    }
                }
            }
        }
    }

    /**
     * 获取歌曲总时长（秒）
     * 通过反射读取 playerInv 中的 CD 物品，获取 SongInfo.songTime
     */
    private static int getSongTimeSeconds(TileEntityMusicPlayer musicPlayer) {
        try {
            if (playerInvField == null) {
                playerInvField = TileEntityMusicPlayer.class.getDeclaredField("playerInv");
                playerInvField.setAccessible(true);
            }
            Object inv = playerInvField.get(musicPlayer);
            if (inv instanceof ItemStackHandler handler) {
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
        return 0;
    }

    /**
     * 通过反射读取 TileEntityMusicPlayer.lyricRecord，并转换为 LRC 格式字符串
     * 返回 null 表示歌词尚未加载
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
