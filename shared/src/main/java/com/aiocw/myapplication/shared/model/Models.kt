package com.aiocw.myapplication.shared.model

/** 一条视频记录。分类 = 所在子文件夹名。 */
data class VideoEntry(
    val key: String,          // 稳定标识：volumeUuid|path，用于收藏/进度持久化
    val path: String,
    val volumeUuid: String?,  // 所在存储卷 UUID；null = 内置存储或未知
    val category: String,     // 一级子文件夹名（=分类）；根目录文件归入 rootCategory
    val title: String,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    val fileName: String get() = path.substringAfterLast('/')

    companion object {
        fun keyOf(volumeUuid: String?, path: String): String = "${volumeUuid ?: "local"}|$path"
    }
}

/** 用户配置的视频源（某个文件夹）。 */
data class VideoSource(
    val id: Long,
    val volumeUuid: String?,
    val absolutePath: String,
    val displayName: String,
    val autoPlayOnMount: Boolean,
)

/** 播放模式（整体随机循环 = LOOP_ALL + shuffle）。 */
enum class PlaybackMode {
    SINGLE_LOOP,   // 单个循环
    LOOP_ALL,      // 整体顺序循环
    LOOP_SHUFFLE,  // 整体随机循环
    OFF            // 顺序播完即停（关闭连播）
}

/** 解码模式。 */
enum class DecodeMode { AUTO, HARDWARE, SOFTWARE }

/** 播放范围：全部 / 收藏 / 指定分类。 */
sealed class LibraryScope {
    object All : LibraryScope()
    object Favorites : LibraryScope()
    data class Category(val name: String) : LibraryScope()
}
