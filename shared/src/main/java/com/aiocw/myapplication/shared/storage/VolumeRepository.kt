package com.aiocw.myapplication.shared.storage

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.aiocw.myapplication.shared.model.VideoSource
import java.io.File

/**
 * 存储卷识别（内置存储 / SD 卡 / U 盘）。
 *
 * 关键点（见鉴权分析 §5）：
 * - U 盘由系统 vold 挂载后通过文件路径访问，【不需要】UsbManager 设备权限；
 * - 禁止硬编码 /storage/XXXX-XXXX 等路径，一律经 StorageManager 获取（OEM 车机路径不统一）；
 * - 卷 UUID（FAT 卷序列号，如 ABCD-1234）在同一只 U 盘上稳定，重格式化才会变，
 *   因此视频源配置按 UUID 持久化，可在换挂载点后自动重新定位。
 */
class VolumeRepository(private val context: Context) {

    data class VolumeInfo(
        val uuid: String?,
        val label: String,
        val root: File?,
        val isRemovable: Boolean,
    )

    fun listVolumes(): List<VolumeInfo> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes.map { it.toInfo() }
    }

    fun listRemovableVolumes(): List<VolumeInfo> = listVolumes().filter { it.isRemovable && it.root != null }

    fun findByUuid(uuid: String?): VolumeInfo? = listVolumes().firstOrNull { it.uuid != null && it.uuid == uuid }

    /**
     * 解析视频源的实际目录：
     * 1. 原绝对路径仍可读 → 直接用；
     * 2. 不可读但记住了卷 UUID → 按 UUID 找到当前挂载点，重定位相对路径；
     * 3. 否则返回 null（U 盘已拔出 / 路径失效）。
     */
    fun resolveSource(source: VideoSource): File? {
        val direct = File(source.absolutePath)
        if (direct.canRead()) return direct

        val uuid = source.volumeUuid ?: return null
        val volume = findByUuid(uuid) ?: return null
        val newRoot = volume.root ?: return null

        val suffix = relativePathInUuid(source.absolutePath, uuid) ?: return null
        val relocated = if (suffix.isEmpty()) newRoot else File(newRoot, suffix)
        return if (relocated.canRead()) relocated else null
    }

    /** 从形如 /storage/<uuid>/xxx 或 /mnt/media_rw/<uuid>/xxx 的路径中截出卷内相对路径。 */
    private fun relativePathInUuid(path: String, uuid: String): String? {
        val marker = "/$uuid/"
        val idx = path.indexOf(marker)
        return if (idx >= 0) path.substring(idx + marker.length) else if (path.endsWith("/$uuid")) "" else null
    }

    fun internalStorageRoot(): File = Environment.getExternalStorageDirectory()

    private fun StorageVolume.toInfo(): VolumeInfo {
        val root: File? = try {
            // StorageVolume.getDirectory()：API 30（Android 11）起公开；本项目 minSdk 30
            directory
        } catch (t: Throwable) {
            uuid?.let { File("/storage/$it") }?.takeIf { it.exists() }
        }
        val label = runCatching { getDescription(context) }.getOrNull() ?: root?.name ?: "存储"
        return VolumeInfo(uuid = uuid, label = label, root = root, isRemovable = isRemovable)
    }
}
