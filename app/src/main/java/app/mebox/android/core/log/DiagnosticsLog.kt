package app.mebox.android.core.log

import android.content.Context
import android.os.Build
import android.util.Log
import app.mebox.android.BuildConfig
import app.mebox.android.core.player.DecoderMode
import app.mebox.android.core.player.SubtitlePriority
import app.mebox.android.core.storage.AppPreferences
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 应用自己的诊断日志。
 *
 * 目标：用户遇到问题时，应用内的日志足够说明「哪一步失败」，
 * 并且能一键导出或从局域网下载，不需要连接 adb 或电脑。
 *
 * 约束：
 * - 只写应用私有目录 `files/logs`，不申请存储权限；
 * - 单个文件 512 KB，最多保留 4 份历史文件，避免长期占用空间；
 * - 不主动记录密码、Authorization 头或带 token 的完整 URL，由调用方保证；
 * - 写入失败绝不影响业务流程（所有磁盘操作都包在 runCatching 里）。
 */
object DiagnosticsLog {

    private const val TAG = "MeBox"
    private const val DIR_NAME = "logs"
    private const val CURRENT_FILE = "mebox.log"
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_ROTATED_FILES = 4

    /** 单条消息上限，避免把超长响应体写进日志。 */
    private const val MAX_MESSAGE_CHARS = 8 * 1024

    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val lock = Any()

    @Volatile
    private var directory: File? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 中调用一次；重复调用无副作用。 */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        synchronized(lock) {
            if (installed) return
            installed = true
            val dir = File(app.filesDir, DIR_NAME)
            runCatching { dir.mkdirs() }
            directory = dir
        }
        installCrashHandler()
        i("App", "==== MeBox 启动 ====")
        i("App", environment())
    }

    fun d(tag: String, message: String) = write('D', tag, message, null)

    fun i(tag: String, message: String) = write('I', tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) =
        write('W', tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        write('E', tag, message, error)

    /** 当前日志文件，新的在前；尚未安装或没有日志时为空。 */
    fun files(): List<LogFileInfo> {
        val dir = directory ?: return emptyList()
        val names = buildList {
            add(CURRENT_FILE)
            for (index in 1..MAX_ROTATED_FILES) add("mebox.$index.log")
        }
        return names.mapNotNull { name ->
            val file = File(dir, name)
            if (!file.isFile) return@mapNotNull null
            LogFileInfo(name = name, sizeBytes = file.length(), lastModified = file.lastModified())
        }
    }

    fun file(name: String): File? {
        if (files().none { it.name == name }) return null
        val dir = directory ?: return null
        return File(dir, name)
    }

    fun totalBytes(): Long = files().sumOf { it.sizeBytes }

    /** 给用户看的运行环境摘要，随日志一起导出。 */
    fun environment(): String {
        val context = appContext
        return buildString {
            appendLine("生成时间: ${LocalDateTime.now().format(formatter)}")
            appendLine(
                "应用: ${BuildConfig.APPLICATION_ID} " +
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS?.joinToString(", ").orEmpty()}")
            appendLine("语言: ${Locale.getDefault()}")
            if (context != null) {
                val preferences = AppPreferences(context)
                appendLine("上次服务器: ${preferences.lastAddress ?: "-"}")
                appendLine("播放内核: ${preferences.playerEngine ?: "默认"}")
                appendLine("解码方式: ${DecoderMode.fromKey(preferences.decoderMode).label}")
                appendLine(
                    "字幕: " + (if (preferences.subtitlesAutoLoad) "自动加载" else "不自动加载") +
                        " / 优先 " + SubtitlePriority.fromKey(preferences.subtitlePriority).label,
                )
            }
            appendLine("日志文件: ${files().size} 个 / ${totalBytes() / 1024} KB")
        }
    }

    fun deleteAll() {
        synchronized(lock) {
            files().forEach { info ->
                runCatching { file(info.name)?.delete() }
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                e("Crash", "未捕获异常 thread=${thread.name}", error)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(level: Char, tag: String, message: String, error: Throwable?) {
        val safeMessage = message.take(MAX_MESSAGE_CHARS)
        val text = buildString {
            append(LocalDateTime.now().format(formatter))
            append(' ').append(level).append('/').append(tag).append(": ").append(safeMessage)
            if (error != null) {
                append('\n').append(error.stackTraceToString())
            }
        }
        runCatching {
            Log.println(priority(level), "$TAG/$tag", text)
        }
        val dir = directory ?: return
        synchronized(lock) {
            runCatching {
                val current = File(dir, CURRENT_FILE)
                rotateIfNeeded(dir, current)
                current.appendText(text + "\n")
            }
        }
    }

    private fun rotateIfNeeded(dir: File, current: File) {
        if (!current.exists() || current.length() < MAX_FILE_BYTES) return
        File(dir, "mebox.$MAX_ROTATED_FILES.log").delete()
        for (index in MAX_ROTATED_FILES - 1 downTo 1) {
            val from = File(dir, "mebox.$index.log")
            if (from.exists()) {
                from.renameTo(File(dir, "mebox.${index + 1}.log"))
            }
        }
        current.renameTo(File(dir, "mebox.1.log"))
    }

    private fun priority(level: Char): Int = when (level) {
        'E' -> Log.ERROR
        'W' -> Log.WARN
        'I' -> Log.INFO
        else -> Log.DEBUG
    }

    data class LogFileInfo(
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )
}
