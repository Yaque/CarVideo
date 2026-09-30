# 车机 Android 11 本地视频播放器 —— 鉴权与权限体系实现技术分析

> 模板基线：<https://github.com/Yaque/My-Application>
> 适用场景：车机 Android 11 本地视频播放器（文件夹视频源 / U 盘 / 分类管理 / 收藏 / 删除 / 多种播放模式 / 硬解+软解）
> 文档性质：实现前的技术分析与鉴权方案设计，含模板工程改造建议

---

## 0. 结论速览

1. **存储鉴权主方案**：`MANAGE_EXTERNAL_STORAGE`（All Files Access，全文件访问）。车机侧载/OEM 预装分发不受 Google Play 审核约束，是最契合"U 盘视频源 + 遍历文件夹 + 直删文件"三要素的路线。
2. **降级方案**：SAF（`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`），仅在未来需要过严格权限环境（如 Play 分发）时启用。
3. **模板需先做一个架构判断**：该模板是 **Car App Library（CAL）模板宿主** 架构（`CarAppActivity` + `android.software.car.templates_host`），**不能直接承载视频画面**。视频播放界面必须用普通 Activity 承载，或在后装普通 Android 11 车机上整体改为常规 Activity 应用（详见 §1.3）。这是动手前最大的分岔点。
4. **初稿的三处技术修正**（本文已按修正后的方案展开）：
   - Media3 **FFmpeg 扩展只提供音频软解**（`FfmpegAudioRenderer`），不含视频软解；视频软解降级应依赖平台自带软解（`c2.android.*`）或 libVLC / ijkplayer / libmpv（详见 §8）。
   - "硬解优先、失败降级软解"应配置 `EXTENSION_RENDERER_MODE_ON`（扩展渲染器排在硬解之后）；`EXTENSION_RENDERER_MODE_PREFER` 是**软解优先**（详见 §8.3）。
   - U 盘访问**不需要** `UsbManager` 设备权限（那套授权弹窗只针对直通 USB 设备节点的应用）。U 盘由系统 vold 挂载后，通过 `StorageManager` / 文件路径访问即可（详见 §5）。

---

## 1. 模板工程基线分析

### 1.1 工程结构与版本基线

| 项 | 值 | 说明 |
|---|---|---|
| 模块 | `:mobile` / `:automotive` / `:shared` | Android for Cars 应用模板 |
| 语言 | Kotlin 2.2.10（AGP 9.3.3） | automotive 模块当前以 Java 11 编译选项为主 |
| compileSdk | 37 | |
| minSdk | 29 | ⚠️ 本项目核心 API 落在 API 30（Android 11），建议 minSdk 提到 **30**，或对 API 30 专属 API（如 `StorageVolume.getDirectory()`）做版本守卫 |
| targetSdk | 37 | 在 Android 11 设备上运行时按 API 30+ 存储规则执行，`requestLegacyExternalStorage` **无效** |
| 关键依赖 | `androidx.car.app:app-automotive:1.7.0`（automotive）、`androidx.car.app:app-projected:1.4.0`（mobile） | CAL（Car App Library） |

automotive 模块 Manifest 关键点（现状）：

```xml
<uses-feature android:name="android.software.car.templates_host" android:required="true" />
<uses-feature android:name="android.hardware.type.automotive" android:required="true" />
...
<meta-data android:name="com.android.automotive" android:resource="@xml/automotive_app_desc" />
<activity android:name="androidx.car.app.activity.CarAppActivity" android:exported="true" ...>
    <meta-data android:name="distractionOptimized" android:value="true" />
</activity>
```

### 1.2 两种目标车机的适配分岔（动手前必须确认）

| | 情况 A：后装普通 Android 11 大屏车机 | 情况 B：原生 AAOS（Android Automotive OS） |
|---|---|---|
| 系统形态 | 普通 Android，无 templates host | 带 Car Service / templates host |
| 模板可用性 | `uses-feature templates_host required=true` 会**阻止安装**，需删除 | 可安装，但 CAL 模板 UI 由宿主渲染 |
| 视频画面承载 | 普通 Activity + `PlayerView`/`SurfaceView` 即可 | ⚠️ CAL 模板**无法承载视频 Surface**；播放界面必须是普通 Activity |
| 常见形态 | 各种国产后装车机、部分前装 | 前装 AAOS 为主 |

**建议**：无论哪种车机，**播放与管理 UI 都用普通 Activity（View 或 Compose）实现**；CAL/模板层仅在确有 AAOS 前装需求时保留为"入口/媒体中心集成"层。若目标是后装 Android 11 车机（最常见），直接：

- 删除 `automotive` Manifest 中 `templates_host` / `type.automotive` 两个 `uses-feature`、`CarAppActivity` 及 `com.android.automotive` meta-data；
- 用常规 `Activity`（含 `PlayerView`）替换模板 UI；
- `:mobile` 模块（Android Auto 投屏）可整体删除——**Android Auto 平台本身禁止视频播放**，该模块对本需求无意义；
- `:shared` 保留并承载全部业务逻辑。

### 1.3 建议的模块职责重构

```
:shared            → 媒体库扫描器（File/StorageManager）、分类模型、收藏/配置持久化（Room/DataStore）、
                     播放模式状态机、权限封装层（StorageAccess 接口 + File/SAF 两实现）
:automotive（或新增 :app） → 视频管理页（分文件夹）、播放页（PlayerView）、设置页（视频源/解码器/播放模式）、
                     权限引导流程、MediaSession 服务
```

> 若必须保留 CAL 入口（AAOS 前装）：`:shared` 的业务层完全复用，CAL 层只做浏览/设置入口，播放跳转到普通 Activity；注意 CAL 应用默认 `distractionOptimized=true`，而**视频属于驾驶受限内容**，播放 Activity 不应标记 distractionOptimized，系统会在行驶中拦截（这正是 AAOS 对视频应用的鉴权/合规要求之一）。

---

## 2. Android 11 存储鉴权模型总览

Android 11（API 30）强制分区存储（Scoped Storage）：

- 应用默认只能自由访问**自身专属目录**（`getExternalFilesDir()` 等）；
- 公共目录中他人应用的文件，即使持 `READ_EXTERNAL_STORAGE`，也不能通过裸文件路径访问；
- `requestLegacyExternalStorage="true"` 在 Android 11 上**完全失效**；
- `WRITE_EXTERNAL_STORAGE` 在 Android 11 上**不再产生任何写能力**（仅剩对 MediaStore 自己条目的有限意义）；
- 本项目跨越的三个鉴权层次：

```
┌──────────────────────────────────────────────┐
│ ① 存储访问权限：MANAGE_EXTERNAL_STORAGE（或 SAF）│  ← 决定能否看到/遍历 U 盘与本地文件夹
├──────────────────────────────────────────────┤
│ ② 媒体索引：MediaStore / MediaScanner          │  ← 可选；仅当需要系统级可见性/缩略图缓存时
├──────────────────────────────────────────────┤
│ ③ 文件操作权限：删除/移动的写能力               │  ← 由 ① 的授权等级决定是否弹系统确认
└──────────────────────────────────────────────┘
```

两路线对比（结合本需求修正版）：

| 维度 | A. MANAGE_EXTERNAL_STORAGE | B. SAF |
|---|---|---|
| U 盘根目录访问 | ✅ 支持 | ⚠️ Android 11 禁止选择存储卷根目录/Download 根目录；U 盘根基本不可选 |
| 遍历/自动扫描 | ✅ `File.listFiles()` 直接递归 | `DocumentFile` 递归，性能差（每次跨 Binder） |
| 删除文件 | ✅ `File.delete()` 直接删 | 可能弹系统确认（`createDeleteRequest`） |
| 批量操作性能 | ✅ 原生 | 差，需 `DocumentsContract` 批量优化 |
| Play 上架 | ❌ 需严格审核（本项目非 Play 分发，不适用） | ✅ 无限制 |
| 车机侧载/OEM 预装 | ✅ 推荐 | 备选 |

**结论：主方案 A，接口层预留 B。** 在 `:shared` 中定义统一抽象：

```kotlin
interface MediaSourceAccess {
    fun listVideoFiles(root: File): List<VideoFile>   // File 实现直接用；SAF 实现内部转 DocumentFile
    fun delete(file: VideoFile): Boolean
    fun exists(file: VideoFile): Boolean
}
```

---

## 3. 路线 A：MANAGE_EXTERNAL_STORAGE（主方案）

### 3.1 Manifest 声明

```xml
<!-- automotive/src/main/AndroidManifest.xml -->
<uses-permission
    android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
    tools:ignore="ScopedStorage" />
<!-- Android 11 上 READ/WRITE_EXTERNAL_STORAGE 保留声明无害（部分 OEM 依赖），但不再提供写能力 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="29" />
```

### 3.2 运行时申请与校验

该权限是**特殊权限**，`requestPermissions()` 不会弹窗，必须跳系统设置页：

```kotlin
fun ensureAllFilesAccess(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.fromParts("package", activity.packageName, null)
            }
            activity.startActivityForResult(intent, REQ_ALL_FILES_ACCESS)
        }
    }
}

// onActivityResult / onResume 中重新校验（用户可能拒绝或事后撤销）
override fun onResume() {
    super.onResume()
    if (!Environment.isExternalStorageManager()) showPermissionGuideDialog()
}
```

要点：

- **生命周期兜底**：`onResume` 每次校验；权限被系统/用户撤销时回退到引导页，而不是运行中崩溃；
- **UX**：首次进入先展示用途说明页（"用于访问 U 盘与本地视频文件夹、执行删除"），再跳设置页。

### 3.3 车机量产环境的静默预授权

车机侧载/预装场景不必依赖用户手点：

```bash
# ADB / 产线工具 / MDM 均可执行
adb shell appops set --uid <package_name> MANAGE_EXTERNAL_STORAGE allow
```

- OEM 预装（系统 app）路径：`/system/priv-app` + privapp-permissions 白名单声明；
- 系统签名应用可进一步获得 `MANAGE_USB`、`MEDIA_CONTENT_CONTROL` 等特许权限（§5.4、§7.2）。

### 3.4 权限边界（必须写进测试用例）

- ❌ 无法访问其他应用专属目录：`/Android/data/*`、`/Android/obb/*`（即使用 MANAGE_EXTERNAL_STORAGE）；
- ❌ 无法读取系统私有目录（`/data`、`/system` 等）；
- ✅ 可访问所有共享存储：内置存储公共目录、SD 卡、**U 盘挂载卷**；
- ✅ 授予后自动获得 `MediaStore.Files` 全表访问能力。

---

## 4. 路线 B：SAF 降级方案（保留接口，暂不启用）

```kotlin
val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
startActivityForResult(intent, REQ_OPEN_TREE)

override fun onActivityResult(...) {
    contentResolver.takePersistableUriPermission(
        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    // 持久化 uri 到配置；用 DocumentFile.fromTreeUri 递归
}
```

Android 11 的额外限制（决定它只能做降级方案）：

- ❌ 不能再选存储卷的**根目录**（内置存储根、SD 卡根、U 盘根）；
- ❌ 不能获取 `Android/data`、`Android/obb` 访问权；
- ❌ Download 目录根不可选；
- U 盘目录能否出现在选择器中取决于 OEM 的 DocumentsProvider 实现，**不可依赖**。

性能注意：`DocumentFile.listFiles()` 每次跨 Binder 调用，大目录（成百上千视频）会明显卡顿，需用 `DocumentsContract` 批量查询优化或缓存。

---

## 5. U 盘（可移动存储卷）访问鉴权与识别

### 5.1 关键澄清：不需要 UsbManager 设备权限

初稿中"USB 权限弹窗 / `UsbManager.getDeviceList()` 触发弹窗"描述的是**直通 USB 设备节点（USB Host API）** 的场景。视频播放器读 U 盘文件走的是完全不同的链路：

```
U 盘插入 → 内核识别 → 系统 vold 挂载到 /storage/<volume> → FUSE 暴露给应用
```

- 挂载完成后，应用通过**文件路径**访问，MANAGE_EXTERNAL_STORAGE 即覆盖；
- 只有打印机、串口、原始 USB 设备类应用才需要 `UsbManager` + 设备授权弹窗；
- **不要**注册 `ACTION_USB_DEVICE_ATTACHED` intent-filter 来"申请 U 盘权限"——这不会带来任何文件访问能力，反而引入每次弹窗的干扰。

### 5.2 正确的 U 盘识别：StorageManager（API 30 提供 `getDirectory()`）

```kotlin
val sm = context.getSystemService(StorageManager::class.java)
val volumes = sm.storageVolumes                    // 含内置存储、SD 卡、U 盘
val usbVolume = volumes.firstOrNull {
    it.isRemovable && !it.isEmulated && it.state == Environment.MEDIA_MOUNTED
}
val usbRoot: File? = usbVolume?.directory           // API 30+；minSdk 29 时做版本守卫
val uuid: String? = usbVolume?.uuid                 // 卷 UUID（FAT 卷序列号，如 "ABCD-1234"）
```

- 路径形态兼容性：不同 OEM 车机 U 盘可能是 `/storage/ABCD-1234`、`/mnt/usb/usbcard1`、`/storage/usb1` 等，**禁止硬编码路径**，一律走 `StorageManager`；
- **配置持久化建议按"卷 UUID + 相对路径"存储**，同时缓存绝对路径：U 盘重新格式化后 UUID 会变，需重新引导用户选择；同一 U 盘换接口/重启后 UUID 稳定，路径解析可自动恢复。

### 5.3 热插拔事件（自动扫描 / 自动播放的前提）

```xml
<receiver android:name=".MediaMountReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.MEDIA_MOUNTED" />
        <action android:name="android.intent.action.MEDIA_EJECT" />
        <data android:scheme="file" />
    </intent-filter>
</receiver>
```

- `MEDIA_MOUNTED` → 触发该卷下视频源目录重扫描；若用户开了"插入自动播放"，拉起播放；
- `MEDIA_EJECT`/`MEDIA_UNMOUNTED` → 停止读取该卷文件、标记视频为"不可用"；
- **播放容错**：U 盘拔出瞬间正在播放的 `MediaItem` 会报 `PlaybackException(ERROR_CODE_IO_FILE_NOT_FOUND)`，应捕获后跳过下一项或暂停提示，避免 ExoPlayer 卡死在错误状态；
- ⚠️ 另注意：U 盘内文件系统多为 FAT32/exFAT/NTFS（部分车机），`File.delete()` 对 FAT32 通常可行；个别 NTFS 挂载为只读（ro）时删除会失败，需捕获返回值并提示。

### 5.4 AAOS/系统签名场景的 USB 特权（仅情况 B 适用）

- 系统签名应用可在 Manifest 声明 `android.permission.MANAGE_USB`（系统权限）绕过 USB 交互确认；
- AAOS 上部分车厂有 `car-usb-handler` 服务统一管理 USB 权限，前装项目需与车厂确认 USB 设备授权策略（部分车机每次开机重新弹窗是 AAOS 生态已知痛点）；
- 若只是读已挂载卷的文件，本条通常不需要——再次强调文件访问与 USB 设备权限是两回事。

---

## 6. 媒体扫描与视频库构建

### 6.1 扫描策略选择

| 策略 | 适用 | 鉴权要求 |
|---|---|---|
| ① `File.listFiles()` 直接递归（**推荐**） | MANAGE_EXTERNAL_STORAGE 已授予 | 无额外 |
| ② `MediaStore.Video.Media` 查询 | 需要系统缩略图/统一索引 | READ_EXTERNAL_STORAGE 或全文件访问 |
| ③ `MediaScannerConnection.scanFile()` | 让文件对系统可见（生成缩略图缓存、其他 App 可见） | 无额外 |

推荐组合：**① 建库 + 按需 ③**。因为：

- 分类需求是"文件夹=分类"，直接用 `file.parentFile.name` 即可，比 `BUCKET_DISPLAY_NAME` 更贴合（BUCKET 是按存储桶分组，嵌套子目录会归到同一 bucket，**不符合"每个子文件夹一个分类"的需求**——初稿此处建议修正）；
- 缩略图/时长用 `MediaMetadataRetriever.setDataSource(filePath)` 直接从文件提取，**无需 MediaStore 索引**；
- `MediaScannerConnection.scanFile()` 保留为可选项：仅在需要系统相册/其他播放器可见时调用。初稿提到的 `Intent.ACTION_MEDIA_SCANNER_SCAN_FILE` 广播确已废弃不可靠，结论一致。

```kotlin
// 视频库扫描骨架（放 :shared，协程 + Dispatchers.IO）
suspend fun scanSourceDir(root: File, uuid: String?): List<VideoEntry> = withContext(Dispatchers.IO) {
    root.walkTopDown()
        .onEnter { it.depth() <= MAX_DEPTH }                 // 限制递归深度（如 3 层）
        .filter { it.isFile && it.extension.lowercase() in VIDEO_EXTS }
        .map { it.toVideoEntry(volumeUuid = uuid) }          // 分类 = it.parentFile.name
        .toList()
}
```

扩展名白名单建议（车机内容生态）：`mp4, mkv, avi, ts, m2ts, mov, wmv, flv, rmvb, rm, mpg, mpeg, vob, 3gp`。

### 6.2 大目录扫描的工程注意

- 扫描放 `Dispatchers.IO` / `WorkManager`，**严禁主线程递归**（U 盘上百 GB 文件会 ANR）；
- 建库结果缓存进 Room（路径、大小、mtime、分类、时长、缩略图路径），重启秒开，增量扫描按 mtime 对比；
- 初次全量扫描与后续增量扫描分层设计。

---

## 7. 播放控制鉴权：Media3 / MediaSession

### 7.1 架构

采用 **Media3（`androidx.media3`）ExoPlayer + MediaSession**，播放模式、播放列表管理原生支持：

```kotlin
val player = ExoPlayer.Builder(context)
    .setRenderersFactory(customRenderersFactory)   // §8 硬解/软解策略
    .build()

player.shuffleModeEnabled = false
player.repeatMode = Player.REPEAT_MODE_ALL
player.setMediaItems(items, startIndex, startPositionMs)
```

### 7.2 外部控制端鉴权

- 外部客户端（系统媒体中心、方向盘按键、语音助手）通过 `MediaSession.Token` / `MediaController` 连接；
- `MEDIA_CONTENT_CONTROL` 是**系统签名特许权限**：普通应用不用也不能申请；系统组件（Assistant、系统播放中心）持有它即可控制你的 Session；
- 普通第三方要控制，走系统媒体通知/媒体按钮广播（`MediaSession.setMediaButtonReceiver`），无需额外权限；
- 车机建议把常用控制（播放/暂停/上一个/下一个/模式切换）都映射到 `onCustomCommand` / `onPlayerCommandRequest`，方便车机系统 UI 与方向盘集成；
- 若为系统签名/预装，可在 Manifest 声明 `android.permission.MEDIA_CONTENT_CONTROL` 以获得双向集成特权。

### 7.3 播放模式映射（需求 → Media3 API）

| 需求 | 配置 | 说明 |
|---|---|---|
| 单个循环 | `repeatMode = REPEAT_MODE_ONE` | 当前视频无限循环 |
| 整体顺序循环 | `repeatMode = REPEAT_MODE_ALL` + `shuffleModeEnabled = false` | 列表播完回到开头 |
| 整体随机循环 | `repeatMode = REPEAT_MODE_ALL` + `shuffleModeEnabled = true` | 洗牌顺序播放 |
| 自动连播 | 默认行为（播放列表推进） | 开关=是否自动 `seekToNextMediaItem()` |
| 不循环 | `repeatMode = REPEAT_MODE_OFF` | 播完停在末尾 |
| 播放指定分类/全部 | 构造对应 `List<MediaItem>` 喂给 player | 管理页/播放页选"全部"或某分类 |

注意：**播放模式状态属于应用配置，不属于鉴权范畴**，用 DataStore 持久化即可；但模式经 MediaSession 暴露给外部控制端（`PlaybackState`）时要同步。

### 7.4 车机媒体相关细节

- 音频焦点：`setAudioAttributes(AudioAttributes.USAGE_MEDIA, /* handleAudioFocus */ true)`，与收音机/蓝牙电话共存（车机多音源抢占是高频 bug 来源）；
- 视频 Activity 与媒体服务解耦：Service 持有 ExoPlayer，Activity 绑定，旋转/熄屏/挂后台不断流；
- Android Auto（`:mobile` 投屏）**平台禁止视频**，视频功能只做车机本机；AAOS 行驶中视频被 UX 限制拦截属预期行为（合规，不是 bug）。

---

## 8. 解码鉴权：硬解与软解

### 8.1 基本事实

- `MediaCodec` 调用系统编解码器**无需任何权限**；
- 解码器可用性依赖 SoC 厂商（高通 `c2.qcom.*`/`OMX.qcom.*`、联发科 `c2.mtk.*`/`OMX.MTK.*`）与系统镜像裁剪；
- Android 11 提供低延迟解码参数（`KEY_LOW_LATENCY`/`PARAMETER_LOW_LATENCY`），对本场景（本地播放）价值不大，可忽略。

### 8.2 解码器探测

```kotlin
val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
val hwDecoder = list.codecInfos.firstOrNull { info ->
    !info.isSoftwareOnly && info.isEncoder.not() &&
    info.supportedTypes.any { it.equals(mime, true) }
}
```

- 排序优先级：厂商硬件解码器 > 平台硬件解码器 > 软件解码器（`c2.android.*`/`OMX.google.*`）；
- 高码率 HEVC/H.264 需用 `CodecCapabilities` 校验 profile/level、分辨率上限，避免硬解实例创建失败后才回退（首帧延迟）。

### 8.3 硬解优先、失败降级软解的正确配置

```kotlin
val factory = DefaultRenderersFactory(context).apply {
    // ON：FFmpeg/扩展渲染器排在 MediaCodec 之后，硬解失败才启用 → 符合"硬解优先、软解降级"
    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    // ⚠️ EXTENSION_RENDERER_MODE_PREFER 是"扩展（软解）优先"，与需求相反
}
```

异常处理：硬解播放中抛 `MediaCodec.CodecException`（`isRecoverable` / `isTransient`，含 `ERROR_RECLAIMED`）时，重建 player 并对该文件降级软解重试，同时把该"文件×解码器"失败记录进本地表（避免下次再踩）。

### 8.4 软解方案选型（初稿修正点）

**重要事实：Media3 的 FFmpeg 扩展（`media3-decoder-ffmpeg`）只提供音频软解（`FfmpegAudioRenderer`），不提供视频软解。** 因此：

| 方案 | 视频软解 | 覆盖格式 | 说明 |
|---|---|---|---|
| 平台自带软解 `c2.android.avc/hevc/vp8/vp9/mpeg4` | ✅ | 主流 MP4/MKV/AVI 内的常见编码 | 零成本，作为通用降级即可满足大多数需求 |
| **libVLC（libvlc for Android）** | ✅ 自带 FFmpeg 全格式 | RMVB/FLV/VOB/TS/M2TS 等全覆盖 | 车机"全格式"需求的最省力方案；LGPL |
| ijkplayer | ✅（FFmpeg） | 全格式 | 项目基本停止维护，API 30 可用但需自行维护 |
| libmpv | ✅（FFmpeg） | 全格式 | 集成稍复杂，LGPL/GPL 需注意分发合规 |

**建议的分层**：主播放链路用 Media3（硬解优先、平台软解兜底，管理/播放列表/MediaSession 生态完善）；若产品要求 RMVB 等冷门格式（车机内容市场常见），为这类文件单独引入 **libVLC 播放内核**（或直接整体采用 libVLC），在 `PlayerEngine` 接口下做双内核路由。许可注意：FFmpeg 系内核按 LGPL/GPL 分发时对闭源 App 的动态链接/开源义务有要求，量产前过一次法务。

### 8.5 设置项设计

- 解码模式：`自动（硬解优先）` / `强制硬解` / `强制软解`，写入 DataStore；
- 实现：`自动`=MODE_ON 顺序；`强制硬解`=只允许 MediaCodec 硬件 codec；`强制软解`=MediaCodecSelector 只返回 `isSoftwareOnly` 或直接走软解内核。

---

## 9. 文件删除鉴权

### 9.1 有全文件访问（主方案）

```kotlin
fun deleteVideo(entry: VideoEntry): Boolean {
    val file = File(entry.path)
    val ok = file.delete()                        // FAT/exFAT 直接删；只读挂载会失败
    if (ok) {
        // 让 MediaStore 同步（可选）；Android 11 FUSE 下直删后 MediaProvider 通常自动同步，
        // 稳妥做法是按 _ID 精确清理或补一次 scanFile 通知
        MediaScannerConnection.scanFile(context, arrayOf(entry.path), null, null)
    }
    return ok
}
```

### 9.2 仅有普通媒体权限时（降级路径）

- 通过 MediaStore 删除：**先查询 `_ID`，再按 `_ID` 删除**（API 30+ 对 `DATA` 列做 selection 有诸多限制，初稿按路径删 MediaStore 记录的写法在 R+ 上不可靠）：

```kotlin
val id = queryIdByPath(entry.path) ?: return false
contentResolver.delete(
    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    "${MediaStore.Video.Media._ID}=?", arrayOf(id.toString()))
```

- 或走 `MediaStore.createDeleteRequest()`（API 30）→ 系统确认弹窗（用户每批确认一次）；
- `createTrashRequest()` 可实现"回收站"式软删除（API 30 支持 trash）。

### 9.3 删除正在播放/在列表中的文件

顺序：先从播放列表移除该 `MediaItem``（`player.removeMediaItem(index)`）并必要时跳到下一项 → 再删物理文件 → 更新 Room。否则会触发 IO 异常播放中断。

### 9.4 管理页删除的批量与确认

- 批量删除：循环 `File.delete()` + 结果统计（成功/失败/跳过正在播放）；
- U 盘只读、文件被占用（个别系统对打开中的文件加锁）要给出明确错误提示，而不是静默失败。

---

## 10. 收藏、配置等本地数据（无需存储鉴权）

- **收藏**：Room 表 `favorites(path, volumeUuid, size, mtime, addedAt)`；用 `volumeUuid + 相对路径 + size/mtime` 做稳定标识，避免换盘符/U 盘重插导致收藏失效；文件缺失时收藏项置灰而非删除；
- **视频源配置**：`VideoSource(id, volumeUuid, absolutePath, displayName, autoPlayOnMount, addedAt)`，支持多个来源（内置存储 + U 盘并存）；
- **播放进度**：Room 单表（path → positionMs），支持"接着播"；
- **设置项**（DataStore）：解码模式、默认播放模式、自动连播开关、随机播放开关、启动自动扫描。

---

## 11. 完整鉴权矩阵（修正版）

| 鉴权层次 | 方案 | 关键 API / 权限 | 车机适配注意 |
|---|---|---|---|
| 存储访问 | MANAGE_EXTERNAL_STORAGE | `Environment.isExternalStorageManager()`、`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` | 支持 ADB `appops set` / MDM / priv-app 预授权 |
| U 盘识别 | StorageManager + 挂载广播 | `StorageManager.getStorageVolumes()`、`StorageVolume.getDirectory()`（API 30） | **无需 UsbManager 权限**；禁止硬编码路径；按卷 UUID 持久化配置 |
| 媒体扫描 | File 直接遍历（+ 可选 scanFile） | `File.walkTopDown()`、`MediaScannerConnection.scanFile()` | 后台线程/协程执行，Room 缓存增量扫描 |
| 缩略图/时长 | MediaMetadataRetriever | 直接文件路径 | 无需 MediaStore 索引 |
| 播放控制 | Media3 MediaSession | `MEDIA_CONTENT_CONTROL`（系统特许，可选） | 方向盘按键/系统媒体中心集成；音频焦点管理 |
| 硬解 | MediaCodec | `MediaCodecList` 探测、厂商 codec 优先 | SoC 驱动差异，profile/level 预校验 |
| 软解 | 平台软解兜底 / libVLC 等内核 | `EXTENSION_RENDERER_MODE_ON` | ⚠️ Media3 FFmpeg 扩展仅音频；视频全格式需 libVLC/ijkplayer/libmpv |
| 文件删除 | `File.delete()`（+ MediaStore `_ID` 清理） | MANAGE_EXTERNAL_STORAGE | 只读挂载失败处理；先移列表后删文件 |
| 收藏/配置 | Room / DataStore | 无 | 用卷 UUID+指纹做稳定键 |
| AAOS 合规（若适用） | UX 限制 | `CarUxRestrictions`、非 distractionOptimized | 行驶中禁止视频为系统合规要求 |

---

## 12. 风险清单与测试用例

| # | 风险 | 测试用例 |
|---|---|---|
| 1 | 权限被用户/系统撤销 | 设置中关闭"所有文件访问"→ App 回引导页不崩溃；重新授予后自动恢复 |
| 2 | U 盘热插拔 | 播放中拔盘→ 播放器跳过/暂停提示；插盘→ 自动扫描（若开启）；UUID 变化（重格式化）→ 引导重选 |
| 3 | 路径差异 | 至少验证 `/storage/XXXX-XXXX`、OEM 自定义路径 2 种机型 |
| 4 | 大目录 ANR | 1000+ 文件 U 盘全量扫描不卡 UI；扫描可取消 |
| 5 | 删除失败 | 只读 U 盘、NTFS ro 挂载、文件被占用 → 明确错误提示 |
| 6 | 解码降级 | 4K HEVC / 高码率 H.264 / 损坏文件 → 硬解失败自动软解或报错跳过 |
| 7 | 冷门格式 | RMVB/FLV/VOB 样片验证软解内核路由 |
| 8 | targetSdk 37 on Android 11 | 真机验证 MANAGE_EXTERNAL_STORAGE 授权页可跳转、生效；`maxSdkVersion` 属性行为 |
| 9 | 播放模式持久化 | 重启后模式/收藏/进度保持 |
| 10 | AAOS 行驶限制（若适用） | 行驶状态播放被拦截属预期，UI 给出提示 |

---

## 13. 实施里程碑建议（结合模板）

1. **M0 模板改造（0.5~1 天）**：确认目标车机类型；按 §1.2/§1.3 裁剪模板（删 CAL/`mobile` 或保留并行）、minSdk 定为 30；
2. **M1 鉴权层（1~2 天）**：`MANAGE_EXTERNAL_STORAGE` 申请/校验/引导流程 + `StorageManager` 卷识别 + 挂载广播接收器 + `MediaSourceAccess` 抽象（File 实现）；
3. **M2 媒体库（2~3 天）**：扫描器 + Room 建库 + 分类模型 + 管理页（分文件夹列表/删除）；
4. **M3 播放器（3~5 天）**：Media3 + MediaSession + 播放页（收藏/删除/模式切换/选分类）+ 三种循环模式 + 连播/随机；
5. **M4 解码策略（2~3 天）**：硬软解切换设置 + 失败降级 +（如需全格式）libVLC 内核接入；
6. **M5 车机联调（3~5 天）**：U 盘热插拔、方向盘按键、音频焦点、真机矩阵测试、量产预授权脚本。

---

## 附录 A：合并后的 Manifest 权限清单（主方案）

```xml
<manifest ...>
    <!-- 存储鉴权（核心） -->
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
        tools:ignore="ScopedStorage" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="29" />

    <!-- U 盘热插拔监听 -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" /> <!-- 可选：开机自动扫描 -->

    <!-- 系统签名/OEM 预装才需要（普通侧载不要声明） -->
    <!-- <uses-permission android:name="android.permission.MANAGE_USB" /> -->
    <!-- <uses-permission android:name="android.permission.MEDIA_CONTENT_CONTROL" /> -->

    <application ...>
        <receiver android:name=".MediaMountReceiver" android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.MEDIA_MOUNTED" />
                <action android:name="android.intent.action.MEDIA_EJECT" />
                <data android:scheme="file" />
            </intent-filter>
        </receiver>
        <!-- 播放 Service（MediaSession 所在）、管理 Activity、播放 Activity、设置 Activity -->
    </application>
</manifest>
```

## 附录 B：参考

- Android 11 存储更新：developer.android.com/about/versions/11/privacy/storage
- `MANAGE_EXTERNAL_STORAGE` 政策与 API：developer.android.com/training/data-storage/manage-all-files
- Media3 / ExoPlayer：developer.android.com/media/media3
- Media3 FFmpeg 扩展（仅音频解码）：github.com/androidx/media → `libraries/decoder_ffmpeg`
- Android for Cars 应用模板：developer.android.com/training/cars
