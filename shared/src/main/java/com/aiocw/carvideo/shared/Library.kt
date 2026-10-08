package com.aiocw.carvideo.shared

import android.content.Context
import com.aiocw.carvideo.shared.data.SettingsRepository
import com.aiocw.carvideo.shared.data.VideoStore
import com.aiocw.carvideo.shared.model.LibraryScope
import com.aiocw.carvideo.shared.model.VideoEntry
import com.aiocw.carvideo.shared.model.VideoSource
import com.aiocw.carvideo.shared.scan.VideoScanner
import com.aiocw.carvideo.shared.storage.FileStorageAccess
import com.aiocw.carvideo.shared.storage.VolumeRepository
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 媒体库聚合入口：扫描 + 缓存 + 收藏 + 范围过滤。
 * 所有耗时操作请在后台线程执行（scan/refresh 内部无协程，调用方控制线程）。
 */
class Library(context: Context) {

    private val appContext = context.applicationContext

    val store = VideoStore(appContext)
    val settings = SettingsRepository(appContext)
    val volumes = VolumeRepository(appContext)
    val access = FileStorageAccess()
    private val scanner = VideoScanner(access)

    @Volatile
    private var cache: List<VideoEntry> = emptyList()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** 全量扫描所有视频源（后台线程调用）。 */
    @Synchronized
    fun refresh(): List<VideoEntry> {
        val sources = store.getSources()
        val entries = scanner.scan(sources, volumes, settings.maxScanDepth)
        cache = entries
        notifyChanged()
        return entries
    }

    /** 仅扫描指定根目录（用于"添加源后预览/校验"）。 */
    fun scanRoot(root: java.io.File): List<VideoEntry> = scanner.scanRoot(root, null, settings.maxScanDepth)

    /** 当前内存快照（未扫描过则为空，需要先 refresh）。 */
    fun snapshot(): List<VideoEntry> = cache

    fun getSources(): List<VideoSource> = store.getSources()

    fun addSource(volumeUuid: String?, absolutePath: String, displayName: String, autoPlay: Boolean) {
        store.addSource(volumeUuid, absolutePath, displayName, autoPlay)
        notifyChanged()
    }

    fun removeSource(id: Long) {
        store.deleteSource(id)
        notifyChanged()
    }

    fun categories(): List<String> = cache.map { it.category }.distinct().sorted()

    fun entriesFor(scope: LibraryScope): List<VideoEntry> = when (scope) {
        is LibraryScope.All -> cache
        is LibraryScope.Favorites -> store.getFavorites()
        is LibraryScope.Category -> cache.filter { it.category == scope.name }
    }

    fun isFavorite(key: String): Boolean = store.isFavorite(key)

    fun toggleFavorite(entry: VideoEntry): Boolean =
        if (store.isFavorite(entry.key)) {
            store.removeFavorite(entry.key); false
        } else {
            store.addFavorite(entry); true
        }

    fun saveProgress(key: String, positionMs: Long) = store.saveProgress(key, positionMs)

    fun getProgress(key: String): Long = store.getProgress(key)

    fun clearProgress(key: String) = store.clearProgress(key)

    fun addOnLibraryChangedListener(l: () -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun removeOnLibraryChangedListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }
}
