package com.hualala.linyu.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 应用内日志记录器。
 *
 * 解决"没电脑/没 adb 就看不了日志"的问题：
 * - 内存环形缓冲：保留最新 [MAX_MEMORY_LOGS] 条，供界面实时查看
 * - 文件滚动：写入本地文件，超过 [MAX_FILE_SIZE] 自动截断，供导出分享
 * - 自动脱敏：手机号 / password / loginCode / secret 等在写入前打码
 *
 * 全局崩溃堆栈由 [installCrashHandler] 捕获后写入。
 */
object AppLogger {

    private const val TAG = "LinYu"
    private const val MAX_MEMORY_LOGS = 300
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024L // 2MB
    private const val LOG_FILE_NAME = "linyu_log.txt"

    private val memoryLogs = ArrayDeque<String>()
    private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileLock = Any()

    @Volatile private var logFile: File? = null
    @Volatile private var ready = false

    fun init(context: Context) {
        if (ready) return
        logFile = File(context.filesDir, LOG_FILE_NAME)
        ready = true
        i("AppLogger 初始化完成")
    }

    fun d(msg: String) = write("D", msg)
    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)

    fun e(msg: String, tr: Throwable? = null) {
        val full = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        write("E", full)
    }

    private fun write(level: String, msg: String) {
        val line = "${LocalDateTime.now().format(timeFmt)} [$level] ${mask(msg)}"
        synchronized(memoryLogs) {
            memoryLogs.addLast(line)
            while (memoryLogs.size > MAX_MEMORY_LOGS) memoryLogs.removeFirst()
        }
        // 同步打到 logcat（方便有 adb 时看）
        runCatching { Log.println(Log.INFO, TAG, line) }
        appendToFile(line)
    }

    private fun appendToFile(line: String) {
        val f = logFile ?: return
        synchronized(fileLock) {
            runCatching {
                if (f.exists() && f.length() > MAX_FILE_SIZE) {
                    // 截断和追加必须共用一把锁，否则并发写入时可能交错或覆盖刚写入的日志。
                    val keep = f.readText().takeLast((MAX_FILE_SIZE / 2).toInt())
                    f.writeText(keep)
                }
                f.appendText(line + "\n")
            }
        }
    }

    /** 敏感信息脱敏，兼容 form（k=v&）与 JSON（"k":"v"）两种格式 */
    private fun mask(s: String): String {
        var r = s
        // 手机号：保留前 3 后 4
        //
        // ⚠️ 前后必须加**数字边界**（`(?<!\d)` / `(?!\d)`）。少了它，任何
        // 「13 开头、后面还有数字」的长串都会被截走 11 位——最典型的就是订单号：
        // `13202609181506275230`（20 位）会被打成 `132****9181506275230`。
        // 那不是脱敏，是把日志里唯一能把订单和账单对上的线索毁掉了：
        // 实测排查结算问题时，日志里的 orderNo 和账单里的 orderNo 因此没法比对。
        r = r.replace(Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")) { m ->
            m.value.take(3) + "****" + m.value.takeLast(4)
        }
        // 敏感字段的值 → ***
        r = r.replace(
            Regex("(?i)(password|loginCode|secret|telPhone|telephone|smsCode)[\"']?\\s*[:=]\\s*[\"']?([^\"'&\\s,}]+)")
        ) { m -> "${m.groupValues[1]}=***" }
        return r
    }

    /** 取内存中的日志（供界面显示） */
    fun getLogs(): List<String> = synchronized(memoryLogs) { memoryLogs.toList() }

    /** 导出用的日志文件 */
    fun logFile(): File? = logFile

    /** 清空内存与文件日志 */
    fun clear() {
        synchronized(memoryLogs) { memoryLogs.clear() }
        synchronized(fileLock) {
            runCatching { logFile?.delete() }
        }
    }

    /**
     * 安装全局未捕获异常处理器：崩溃时先把堆栈写进日志，再交给系统默认处理。
     * 这样用户下次打开 App 就能在日志里看到崩溃原因。
     */
    fun installCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                e("未捕获异常 @ ${thread.name}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
