package com.aiocw.myapplication.shared.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aiocw.myapplication.shared.model.VideoEntry
import com.aiocw.myapplication.shared.model.VideoSource

/**
 * 本地持久化：视频源 / 收藏 / 播放进度。
 * 用框架 SQLite 实现（零外部依赖、无 KSP/Room 版本耦合），接口保持 DAO 风格，后续可平滑换 Room。
 */
class VideoStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sources (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                volume_uuid TEXT,
                absolute_path TEXT NOT NULL,
                display_name TEXT NOT NULL,
                auto_play INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE favorites (
                key TEXT PRIMARY KEY,
                path TEXT NOT NULL,
                volume_uuid TEXT,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                last_modified INTEGER NOT NULL,
                added_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE progress (
                key TEXT PRIMARY KEY,
                position_ms INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 为初版；升级策略后续按需补充
    }

    // ---------- 视频源 ----------

    fun addSource(volumeUuid: String?, absolutePath: String, displayName: String, autoPlay: Boolean): Long {
        val cv = ContentValues().apply {
            put("volume_uuid", volumeUuid)
            put("absolute_path", absolutePath)
            put("display_name", displayName)
            put("auto_play", if (autoPlay) 1 else 0)
            put("added_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("sources", null, cv)
    }

    fun getSources(): List<VideoSource> = readableDatabase.rawQuery(
        "SELECT id, volume_uuid, absolute_path, display_name, auto_play FROM sources ORDER BY added_at", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    VideoSource(
                        id = c.getLong(0),
                        volumeUuid = c.getString(1),
                        absolutePath = c.getString(2),
                        displayName = c.getString(3),
                        autoPlayOnMount = c.getInt(4) == 1,
                    )
                )
            }
        }
    }

    fun deleteSource(id: Long) {
        writableDatabase.delete("sources", "id=?", arrayOf(id.toString()))
    }

    // ---------- 收藏 ----------

    fun isFavorite(key: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM favorites WHERE key=?", arrayOf(key)
    ).use { it.moveToFirst() }

    fun addFavorite(entry: VideoEntry) {
        val cv = ContentValues().apply {
            put("key", entry.key)
            put("path", entry.path)
            put("volume_uuid", entry.volumeUuid)
            put("category", entry.category)
            put("title", entry.title)
            put("size_bytes", entry.sizeBytes)
            put("last_modified", entry.lastModified)
            put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("favorites", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeFavorite(key: String) {
        writableDatabase.delete("favorites", "key=?", arrayOf(key))
    }

    fun getFavorites(): List<VideoEntry> = readableDatabase.rawQuery(
        "SELECT key, path, volume_uuid, category, title, size_bytes, last_modified FROM favorites ORDER BY added_at DESC",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    VideoEntry(
                        key = c.getString(0),
                        path = c.getString(1),
                        volumeUuid = c.getString(2),
                        category = c.getString(3),
                        title = c.getString(4),
                        sizeBytes = c.getLong(5),
                        lastModified = c.getLong(6),
                    )
                )
            }
        }
    }

    // ---------- 播放进度 ----------

    fun saveProgress(key: String, positionMs: Long) {
        if (key.isBlank()) return
        val cv = ContentValues().apply {
            put("key", key)
            put("position_ms", positionMs)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("progress", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getProgress(key: String): Long = readableDatabase.rawQuery(
        "SELECT position_ms FROM progress WHERE key=?", arrayOf(key)
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun clearProgress(key: String) {
        writableDatabase.delete("progress", "key=?", arrayOf(key))
    }

    companion object {
        private const val DB_NAME = "car_video.db"
        private const val DB_VERSION = 1
    }
}
