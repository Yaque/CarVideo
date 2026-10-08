# 车视（车机 Android 11 本地视频播放器）

包名 `com.aiocw.carvideo`，应用名「车视」。

基于 [Yaque/My-Application](https://github.com/Yaque/My-Application)（Android for Cars 模板）改造：
移除 Android Auto 投屏（`:mobile`）与 Car App Library 模板宿主（CAL 不承载视频 Surface），
`:app` 采用常规 Activity + Media3 架构，可直接运行在 Android 11 车机（后装大屏 / AAOS 均可）。

设计文档：《车机Android11视频播放器-鉴权技术分析.md》（工程根目录）。

## 功能

- **视频源**：设置页选择本地文件夹作为视频源（内置存储 / SD 卡 / **U 盘**），可配置多个
- **分类**：视频源内每个子文件夹 = 一个分类；管理页分文件夹管理
- **管理页**：全部 / 收藏 / 分类浏览，删除视频（含 U 盘文件），扫描
- **播放页**：收藏按钮、删除当前视频、播放范围切换（全部 / 收藏 / 任意分类）
- **上下滑动（抖音式）**：视频随手指 1:1 跟手位移，邻近视频封面随动露出；
  松手按滑动距离 / 甩动速度决定切换或回弹，切换用缩略图衔接首帧，无黑屏；单击暂停/继续
- **界面字体**：全局统一字号开关（80% ~ 150%，设置页 / 播放菜单均可调），所有页面/弹窗/列表同步缩放
- **白天 / 黑夜 / 自动**：三套外观随心切换（设置页三选一 / 播放菜单循环），
  浅色/深色玻璃拟态配色 + 壁纸色调自动适配，选“跟随系统”时随系统昼夜自动切换
- **播放模式**：单个循环 / 整体顺序循环 / 整体随机循环 / 顺序播完（即"关闭自动连播"）
- **自动行为**：U 盘插入自动扫描、可选自动随机播放；断点续播
- **解码**：自动（硬解优先、失败降级软解）/ 强制硬解 / 强制软解

## 模块

```
:app      UI + 播放内核
  ui/            MainActivity（管理页）、ImmersiveActivity（抖音式播放页）、SettingsActivity、
                 FolderPickerDialog、FontScale（全局字号缩放）、UiConfig（字号+昼夜统一配置）、
                 ThumbLoader（滑动封面）、glass/（毛玻璃基础设施，昼夜双配色）
  playback/      PlaybackService（ExoPlayer + MediaSession）、DecoderSelector、PlayerModes、PlaylistBuilder
  receiver/      MediaMountReceiver（U 盘热插拔）
:shared   业务/数据层
  model/         VideoEntry、VideoSource、PlaybackMode、DecodeMode、LibraryScope
  storage/       VolumeRepository（StorageManager 卷识别）、StorageAccess（File/SAF 抽象）
  scan/          VideoScanner（File 遍历，子文件夹=分类）
  data/          VideoStore（SQLite：视频源/收藏/进度）、SettingsRepository
  util/          VideoMetadata（时长/缩略图，无需 MediaStore 索引）
  Library.kt     媒体库聚合入口
```

> 注：源码包结构为 `com.aiocw.carvideo.*`（`:app` 与 `:shared` 同构）。

## 构建

用 Android Studio 打开根目录即可（AGP 9.3.3 / Gradle 9.5 / 内置 Kotlin，无需额外 Kotlin 插件）。

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 鉴权要点（详见分析文档）

1. **主权限**：`MANAGE_EXTERNAL_STORAGE`（"所有文件访问"）——覆盖 U 盘遍历、删除、分类扫描；
2. **U 盘**：`StorageManager.getStorageVolumes()` + `StorageVolume.getDirectory()` 识别（按卷 UUID 持久化视频源配置），
   热插拔监听 `MEDIA_MOUNTED/EJECT`；**不需要** UsbManager 设备权限；
3. **量产预授权**（ADB / MDM / 产线）：

   ```bash
   adb shell appops set --uid com.aiocw.carvideo MANAGE_EXTERNAL_STORAGE allow
   ```

4. **删除**：`File.delete()` 直删 + 收藏/进度清理 + 重扫；先从播放列表移除再删文件；
5. **播放控制**：Media3 MediaSession，方向盘按键/系统媒体中心可直连；`MEDIA_CONTENT_CONTROL` 仅系统签名需要（已注释预留）；
6. **解码**：`EXTENSION_RENDERER_MODE_ON`（硬解优先、失败降级），按 codec 名称前缀强制硬/软解。

## 已知边界

- 冷门格式（RMVB/FLV/VOB 等）的视频软解需引入 libVLC / ijkplayer / libmpv 内核（见分析文档 §8.4），
  当前实现覆盖 Android 平台软解可解的主流格式（MP4/MKV/AVI/TS/WEBM…）；
- Android 12+ 上"U 盘插入自动播放"受后台启动 Activity/前台服务限制，行为以 Android 11 为准；
- `minSdk = 30`（目标系统 Android 11）。
