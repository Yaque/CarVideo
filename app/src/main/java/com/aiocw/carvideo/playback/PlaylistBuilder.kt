package com.aiocw.carvideo.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.aiocw.carvideo.shared.Library
import com.aiocw.carvideo.shared.model.LibraryScope
import com.aiocw.carvideo.shared.model.VideoEntry
import java.io.File

/** VideoEntry → Media3 MediaItem（mediaId = 稳定 key，收藏/进度/删除都以它对齐）。 */
fun VideoEntry.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(key)
        .setUri(Uri.fromFile(File(path)))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(category)
                .build()
        )
        .build()

/** 播放范围 → Media3 播放列表。 */
object PlaylistBuilder {

    /** 在调用线程执行（可能触发全量扫描），请务必放在后台线程。 */
    fun build(library: Library, scope: LibraryScope): List<MediaItem> {
        var entries = library.entriesFor(scope)
        if (entries.isEmpty() && scope !is LibraryScope.Favorites) {
            entries = if (scope is LibraryScope.Category) {
                library.refresh().filter { it.category == scope.name }
            } else {
                library.refresh()
            }
        }
        return entries.map { it.toMediaItem() }
    }
}
