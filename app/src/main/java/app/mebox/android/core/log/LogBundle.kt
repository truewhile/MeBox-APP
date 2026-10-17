package app.mebox.android.core.log

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把日志打包成用户可分享的 ZIP。
 *
 * ZIP 内含 `diagnostics.txt`（设备、版本、服务器、播放内核等摘要）
 * 和全部日志文件；不包含账号密码或 Authorization 头。
 */
object LogBundle {

    private val nameFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

    private const val MIME_ZIP = "application/zip"

    fun createBundle(context: Context): File = createBundle(context.cacheDir)

    /** 供局域网服务和测试使用：直接指定 ZIP 的输出目录。 */
    fun createBundle(cacheDir: File): File {
        val stamp = LocalDateTime.now().format(nameFormatter)
        val zip = File(cacheDir, "mebox-logs-$stamp.zip")
        ZipOutputStream(BufferedOutputStream(zip.outputStream())).use { output ->
            output.putNextEntry(ZipEntry("diagnostics.txt"))
            output.write(DiagnosticsLog.environment().toByteArray(Charsets.UTF_8))
            output.closeEntry()

            DiagnosticsLog.files().forEach { info ->
                val source = DiagnosticsLog.file(info.name) ?: return@forEach
                output.putNextEntry(ZipEntry(info.name))
                source.inputStream().use { input -> input.copyTo(output) }
                output.closeEntry()
            }
        }
        return zip
    }

    /**
     * 把 ZIP 写入用户通过系统文件选择器指定的文件夹。
     *
     * 使用 SAF：不需要申请存储权限，也不依赖具体厂商的路径规则。
     * 返回实际使用的文件名，失败时返回异常信息。
     */
    fun exportToFolder(context: Context, treeUri: Uri): Result<String> = runCatching {
        val zip = createBundle(context)
        try {
            val resolver = context.contentResolver
            // OpenDocumentTree 返回的是 tree URI，createDocument 需要该树的文档 URI。
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val created = DocumentsContract.createDocument(
                resolver,
                parent,
                MIME_ZIP,
                zip.name,
            ) ?: error("无法在所选文件夹中创建文件，请换一个文件夹")
            resolver.openOutputStream(created)?.use { stream ->
                zip.inputStream().use { it.copyTo(stream) }
            } ?: error("无法写入所选文件夹，请确认已授予写入权限")
            zip.name
        } finally {
            runCatching { zip.delete() }
        }
    }

    /** 供局域网页面展示；把字节数变成便于阅读的 KB/MB。 */
    fun readableSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
