package com.aiocw.myapplication.shared.storage

import android.os.Environment
import java.io.File

/**
 * 存储访问抽象：把"全文件访问（MANAGE_EXTERNAL_STORAGE）"与"SAF 降级"统一成一套接口。
 *
 * 当前实现 [FileStorageAccess] 依赖 MANAGE_EXTERNAL_STORAGE（主方案，见鉴权分析 §3）；
 * 若未来需适配严格权限环境（如 Play 分发），新增 SafStorageAccess 实现本接口即可，
 * 业务层（扫描/删除/播放）无需改动。
 */
interface StorageAccess {
    fun hasAccess(): Boolean
    fun canRead(file: File): Boolean
    fun listFiles(dir: File): List<File>
    fun delete(file: File): Boolean
}

class FileStorageAccess : StorageAccess {

    override fun hasAccess(): Boolean =
        Environment.isExternalStorageManager()

    override fun canRead(file: File): Boolean = file.canRead()

    override fun listFiles(dir: File): List<File> =
        dir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()

    override fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)
}
