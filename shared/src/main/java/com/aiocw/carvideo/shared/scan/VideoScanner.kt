package com.aiocw.carvideo.shared.scan

import com.aiocw.carvideo.shared.model.VideoEntry
import com.aiocw.carvideo.shared.model.VideoSource
import com.aiocw.carvideo.shared.storage.StorageAccess
import com.aiocw.carvideo.shared.storage.VolumeRepository
import java.io.File

/**
 * 视频扫描器：按配置的视频源目录递归扫描，一个子文件夹 = 一个分类。
 *
 * 采用 File 直接遍历（MANAGE_EXTERNAL_STORAGE 主方案），不依赖 MediaStore 索引；
 * 缩略图/时长由 MediaMetadataRetriever 直接从文件提取，同样无需 MediaStore。
 */
class VideoScanner(private val access: StorageAccess) {

    fun scan(
        sources: List<VideoSource>,
        volumeRepository: VolumeRepository,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): List<VideoEntry> {
        val result = LinkedHashMap<String, VideoEntry>()
        for (source in sources) {
            val root = volumeRepository.resolveSource(source) ?: continue // U 盘拔出 → 该源暂不可用
            scanRoot(root, source.volumeUuid, maxDepth).forEach { result.putIfAbsent(it.key, it) }
        }
        return result.values.toList()
    }

    fun scanRoot(root: File, volumeUuid: String?, maxDepth: Int = DEFAULT_MAX_DEPTH): List<VideoEntry> {
        if (!root.canRead()) return emptyList()
        val entries = ArrayList<VideoEntry>()
        walk(root, root, volumeUuid, depth = 0, maxDepth = maxDepth, out = entries)
        return entries
    }

    private fun walk(
        root: File,
        dir: File,
        volumeUuid: String?,
        depth: Int,
        maxDepth: Int,
        out: MutableList<VideoEntry>,
    ) {
        val children = access.listFiles(dir) ?: return
        for (f in children) {
            if (f.isDirectory) {
                if (depth < maxDepth) walk(root, f, volumeUuid, depth + 1, maxDepth, out)
            } else if (isVideoFile(f)) {
                out += VideoEntry(
                    key = VideoEntry.keyOf(volumeUuid, f.absolutePath),
                    path = f.absolutePath,
                    volumeUuid = volumeUuid,
                    category = categoryOf(root, f),
                    title = f.nameWithoutExtension,
                    sizeBytes = f.length(),
                    lastModified = f.lastModified(),
                )
            }
        }
    }

    /** 分类 = 文件所在的一级子文件夹名；根目录下的视频归入视频源目录名。 */
    private fun categoryOf(root: File, file: File): String {
        val parent = file.parentFile ?: return root.name
        return if (parent.absolutePath == root.absolutePath) root.name else parent.name
    }

    private fun isVideoFile(f: File): Boolean =
        f.extension.lowercase() in VIDEO_EXTENSIONS

    companion object {
        const val DEFAULT_MAX_DEPTH = 3

        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "ts", "m2ts", "mov", "wmv", "flv",
            "rmvb", "rm", "mpg", "mpeg", "vob", "3gp", "webm", "m4v",
        )
    }
}
