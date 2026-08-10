# Album lyrics display

独立桥接模组：让 Create 的 **Display Link（显示链接器）** 能读取 NetMusic **Music Player（唱片机）** 的歌词，在 **翻牌显示器 (Flap Display / Display Board)** 上同步显示。

**不修改 Create 或 NetMusic 的任何源代码。**

---

## 工作原理

```
┌──────────────── 客户端 ────────────────┐
│ NetMusic 唱片机播放音乐                  │
│   ↓ lyricRecord 字段被填充（客户端）     │
│ NMCB 检测到 isPlay=true                  │
│   ↓ 通过反射读取 lyricRecord             │
│ 转换为 LRC 格式 + 获取歌曲时长            │
│   ↓ 发送 LyricToServerMessage            │
└─────────────────────────────────────────┘
                    ↓
┌──────────────── 服务端 ────────────────┐
│ 收到 LRC 歌词 + 歌曲时长                 │
│   ↓ 存储在唱片机 PersistentData 中       │
│ Display Link 检测到唱片机方块             │
│   ↓ NetMusicLyricDisplaySource 被激活    │
│ 读取 PersistentData 中的 LRC             │
│   ↓ 根据 currentTime 计算当前歌词行       │
│ transferData() → DisplayBoardTarget      │
│   ↓ 翻牌显示器显示歌词                    │
└─────────────────────────────────────────┘
```

### 关键技术点

1. **歌词获取**：客户端通过反射访问 `TileEntityMusicPlayer.lyricRecord`（NetMusic 的客户端字段）
2. **歌词传输**：自定义网络包 `LyricToServerMessage` 将 LRC 文本从客户端发送到服务端
3. **歌词存储**：存储在唱片机方块实体的 `PersistentData`（NBT），不修改原模组代码
4. **歌词计算**：DisplaySource 在服务端解析 LRC，根据 `currentTime`（倒计时 tick）计算当前行
5. **DisplaySource 注册**：通过 Create 的 API 注册表注册，关联到 `netmusic:music_player` 方块实体类型

---

## 使用方法

1. 放置一个 **NetMusic 唱片机**，放入一张有歌词的网易云音乐 CD
2. 播放音乐，等待歌词加载（仅网易云音乐支持歌词）
3. 在唱片机旁边放置 **Display Link（显示链接器）**，面朝唱片机
4. 在远处放置 **翻牌显示器 (Flap Display)**
5. 右键 Display Link，配置 Target 指向翻牌显示器
6. 给 Display Link 一个**红石信号**触发数据传输
7. 翻牌显示器将同步显示当前歌词！

### 注意事项

- 歌词每 10 tick更新一次
- 仅支持网易云音乐来源的 CD（URL 包含 `music.163.com`）
- 需要在 NetMusic 配置中启用歌词功能 (`ENABLE_PLAYER_LYRICS`)
- 翻牌显示器需要足够的转速才能正常工作

---

## 依赖

| 模组 | 版本 | 说明 |
|------|------|------|
| Forge | 47.4.x | Minecraft 1.20.1 模组加载器 |
| Create | 6.0.x | 机械动力模组 |
| NetMusic | 1.x | 网易云音乐唱片机模组 |
