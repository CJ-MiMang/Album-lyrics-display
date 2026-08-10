package com.example.nmcb;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayBoardTarget;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayLayout;
import com.simibubi.create.content.trains.display.FlapDisplaySection;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import com.simibubi.create.foundation.utility.CreateLang;

import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Create Display Source: 从 NetMusic 唱片机读取歌词
 *
 * 支持三种显示模式：
 * - 模式 0：仅显示第一行歌词（原文）
 * - 模式 1：仅显示第二行歌词（翻译）
 * - 模式 2：同时显示两行歌词（原文 + 翻译）
 *
 * 歌词数据来源：
 * 1. 客户端通过反射读取唱片机的 lyricRecord，转换为 LRC 格式发送到服务端
 * 2. 服务端将 LRC 存储在唱片机方块实体的 PersistentData 中
 * 3. 本类在服务端读取 LRC，根据当前播放时间计算当前歌词行
 */
public class NetMusicLyricDisplaySource extends DisplaySource {
    private static final Pattern LRC_PATTERN = Pattern.compile("\\[(\\d+):(\\d+)[.:](\\d+)](.*)");

    // 缓存解析结果
    private String cachedLrcContent = "";
    private Int2ObjectSortedMap<String> cachedOriginalLyrics = null;
    private Int2ObjectSortedMap<String> cachedTranslatedLyrics = null;

    // ========== 核心数据提供方法 ==========

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        BlockEntity sourceBE = context.getSourceBlockEntity();
        if (sourceBE == null) return EMPTY;

        CompoundTag data = sourceBE.getPersistentData();
        String lrcContent = data.getString(NetMusicBridgeEvents.LRC_NBT_KEY);
        if (lrcContent == null || lrcContent.isEmpty()) return EMPTY;

        // 解析 LRC（带缓存，分离原文和翻译）
        if (!lrcContent.equals(cachedLrcContent)) {
            cachedLrcContent = lrcContent;
            parseLrc(lrcContent);
        }

        int playedTicks = calculatePlayedTicks(data);
        if (playedTicks <= 0) return EMPTY;

        // 获取当前配置的显示模式
        int mode = context.sourceConfig().getInt("Mode");

        String originalLine = getCurrentLine(cachedOriginalLyrics, playedTicks);
        String translatedLine = getCurrentLine(cachedTranslatedLyrics, playedTicks);

        // 根据模式返回对应的歌词行
        switch (mode) {
            case 0 -> {
                // 仅原文
                if (originalLine.isEmpty()) return EMPTY;
                MutableComponent line = Component.literal(originalLine);
                line = applyLabel(context, line);
                return ImmutableList.of(line);
            }
            case 1 -> {
                // 仅翻译
                if (translatedLine.isEmpty()) return EMPTY;
                MutableComponent line = Component.literal(translatedLine);
                line = applyLabel(context, line);
                return ImmutableList.of(line);
            }
            case 2 -> {
                // 两行都显示
                ImmutableList.Builder<MutableComponent> builder = ImmutableList.builder();
                if (!originalLine.isEmpty()) {
                    builder.add(Component.literal(originalLine));
                }
                if (!translatedLine.isEmpty()) {
                    builder.add(Component.literal(translatedLine));
                }
                List<MutableComponent> result = builder.build();
                return result.isEmpty() ? EMPTY : result;
            }
            default -> {
                return EMPTY;
            }
        }
    }

    @Override
    public List<List<MutableComponent>> provideFlapDisplayText(DisplayLinkContext context, DisplayTargetStats stats) {
        int mode = context.sourceConfig().getInt("Mode");

        if (mode != 2) {
            // 模式 0 和 1：单行，使用默认行为
            return super.provideFlapDisplayText(context, stats);
        }

        // 模式 2：两行，每行一个独立的 Component 列表
        List<MutableComponent> textLines = provideText(context, stats);
        if (textLines.isEmpty()) return List.of();

        ImmutableList.Builder<List<MutableComponent>> result = ImmutableList.builder();
        for (MutableComponent line : textLines) {
            result.add(ImmutableList.of(line));
        }
        return result.build();
    }

    // ========== 翻牌显示器布局 ==========

    @Override
    public void loadFlapDisplayLayout(DisplayLinkContext context, FlapDisplayBlockEntity flapDisplay,
                                       FlapDisplayLayout layout) {
        int mode = context.sourceConfig().getInt("Mode");
        String layoutKey = getFlapDisplayLayoutName(context);

        int maxCharCount = flapDisplay.getMaxCharCount();

        if (mode != 2) {
            // 单行模式：使用默认布局
            if (!layout.isLayout(layoutKey))
                layout.configure(layoutKey,
                        ImmutableList.of(new FlapDisplaySection(maxCharCount * FlapDisplaySection.MONOSPACE, "alphabet", false, false)));
            return;
        }

        // 两行模式：布局包含标签 + 内容
        String label = context.sourceConfig().getString("Label");
        if (label.isEmpty()) {
            if (!layout.isLayout(layoutKey))
                layout.configure(layoutKey,
                        ImmutableList.of(new FlapDisplaySection(maxCharCount * FlapDisplaySection.MONOSPACE, "alphabet", false, false)));
            return;
        }

        String layoutName = label.length() + "_Labeled_" + layoutKey;
        if (layout.isLayout(layoutName)) return;

        FlapDisplaySection labelSection = new FlapDisplaySection(
                Math.min(maxCharCount, label.length() + 1) * FlapDisplaySection.MONOSPACE, "alphabet", false, false);

        if (label.length() + 1 < maxCharCount)
            layout.configure(layoutName,
                    ImmutableList.of(labelSection, new FlapDisplaySection((maxCharCount - label.length() - 1) * FlapDisplaySection.MONOSPACE, "alphabet", false, false)));
        else
            layout.configure(layoutName, ImmutableList.of(labelSection));
    }

    protected String getFlapDisplayLayoutName(DisplayLinkContext context) {
        return "Default";
    }

    // ========== 配置 UI ==========

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initConfigurationWidgets(DisplayLinkContext context, ModularGuiLineBuilder builder, boolean isFirstLine) {
        if (isFirstLine) {
            // 标签输入框
            builder.addTextInput(0, 137, (e, t) -> {
                e.setValue("");
                t.withTooltip(ImmutableList.of(
                        CreateLang.translateDirect("display_source.label")
                                .withStyle(s -> s.withColor(0x5391E1)),
                        CreateLang.translateDirect("gui.schedule.lmb_edit")
                                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY, net.minecraft.ChatFormatting.ITALIC)));
            }, "Label");
        } else {
            // 模式选择：原文 / 翻译 / 双语
            builder.addSelectionScrollInput(0, 95, (selectionScrollInput, label) -> {
                selectionScrollInput
                        .forOptions(CreateLang.translatedOptions("display_source.nmcb.netmusic_lyric",
                                "original", "translated", "bilingual"));
            }, "Mode");
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 对歌词行应用标签前缀
     */
    private MutableComponent applyLabel(DisplayLinkContext context, MutableComponent line) {
        String label = context.sourceConfig().getString("Label");
        if (!label.isEmpty()) {
            return Component.literal(label + " ").append(line);
        }
        return line;
    }

    /**
     * 从歌词映射中获取当前应显示的歌词行
     */
    private String getCurrentLine(Int2ObjectSortedMap<String> lyrics, int playedTicks) {
        if (lyrics == null || lyrics.isEmpty()) return "";

        String result = "";
        for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<String> entry : lyrics.int2ObjectEntrySet()) {
            if (entry.getIntKey() <= playedTicks) {
                result = entry.getValue();
            } else {
                break;
            }
        }
        return result;
    }

    /**
     * 计算已播放的 tick 数
     *
     * NetMusic 的 currentTime 是倒计时：从 (songTime*20+64) 递减到 0
     * 客户端发送 LRC 时附带 songTimeSeconds 和客户端当时读到的 currentTime
     * 服务端通过 (songTime*20+64 - currentTime) 计算已播放 tick
     */
    private int calculatePlayedTicks(CompoundTag data) {
        int currentTime = data.getInt("CurrentTime");
        if (currentTime <= 0) return 0;

        // 获取客户端发送 LRC 时服务端的 currentTime 值
        int initialCurrentTime = data.getInt(NetMusicBridgeEvents.INITIAL_CURRENT_TIME_NBT_KEY);
        int songTimeSeconds = data.getInt(NetMusicBridgeEvents.SONG_TIME_NBT_KEY);

        if (songTimeSeconds > 0 && initialCurrentTime > 0) {
            // 计算客户端发送 LRC 时已经播放了多少 tick
            int totalTicks = songTimeSeconds * 20 + 64;
            int ticksBeforeLrc = totalTicks - initialCurrentTime;
            // 从那以后又播放了多少 tick
            int ticksSinceLrc = initialCurrentTime - currentTime;
            return ticksBeforeLrc + ticksSinceLrc;
        }

        // 后备方案：如果没有初始值信息，估算
        if (songTimeSeconds > 0) {
            int totalTicks = songTimeSeconds * 20 + 64;
            return totalTicks - currentTime;
        }

        return Integer.MAX_VALUE;
    }

    /**
     * 解析 LRC 格式歌词，分离原文和翻译
     *
     * LRC 格式中，原文和翻译通过空行分隔：
     * [00:00.00]原文第一行
     * [00:05.00]原文第二行
     *                    ← 空行分隔
     * [00:00.00]翻译第一行
     * [00:05.00]翻译第二行
     */
    private void parseLrc(String lrcContent) {
        cachedOriginalLyrics = new Int2ObjectRBTreeMap<>();
        cachedTranslatedLyrics = null;

        if (lrcContent == null || lrcContent.isEmpty()) return;

        String[] sections = lrcContent.split("\n\n", 2);
        cachedOriginalLyrics = parseLrcSection(sections[0]);

        if (sections.length > 1 && !sections[1].trim().isEmpty()) {
            cachedTranslatedLyrics = parseLrcSection(sections[1]);
        }
    }

    /**
     * 解析一个 LRC 段落为 tick -> text 映射
     * tick 单位：1 tick = 50ms（与 LRC 时间戳对应）
     */
    private static Int2ObjectSortedMap<String> parseLrcSection(String section) {
        Int2ObjectSortedMap<String> lyrics = new Int2ObjectRBTreeMap<>();
        if (section == null || section.isEmpty()) return lyrics;

        String[] lines = section.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher matcher = LRC_PATTERN.matcher(line);
            if (matcher.find()) {
                int minutes = Integer.parseInt(matcher.group(1));
                int seconds = Integer.parseInt(matcher.group(2));
                int milliseconds = Integer.parseInt(matcher.group(3));
                String text = matcher.group(4).trim();
                if (!text.isEmpty()) {
                    int totalTick = ((minutes * 60 + seconds) * 1000 + milliseconds) / 50;
                    lyrics.put(totalTick, text);
                }
            }
        }
        return lyrics;
    }

	@Override
	public int getPassiveRefreshTicks() { return 10; }

    @Override
    protected String getTranslationKey() {
        return "netmusic_lyric";
    }
}
