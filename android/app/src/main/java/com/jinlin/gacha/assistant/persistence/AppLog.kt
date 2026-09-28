package com.jinlin.gacha.assistant.persistence

import android.content.Context
import android.util.Log
import com.jinlin.gacha.assistant.core.CaptureLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * **App 级日志落盘器**（2026-09-22 新增；需求出处 `docs/待办事项.md`「异常自动落盘日志扩展到整个 app」）。
 *
 * ### 它要解决什么
 * 原有两处日志出口**都绑在抓包会话上**：
 * - logcat —— 要连电脑看，用户自己看不到；
 * - `DiagnoseDumper` 的同名 `.txt` 诊断报告 —— 只在**抓包中**点「抓诊断」才落盘。
 *
 * 于是「开抓包之前 / 停抓之后」App 出的事（元数据拉取失败、导入出错、目标游戏没检出…）
 * **一条都留不下来**。本 object 补的就是这一段：**开关一开就写、一关就停**，与会话无关。
 *
 * ### 落点与保留
 * `getExternalFilesDir(null)/applog/app-<yyyyMMdd>.txt` —— 外部私有目录，**按天一个文件**；
 * 跨天自动换新文件，并清掉早于 [KEEP_DAYS] 天的旧文件。同目录文件在「设置 → 数据清理」里
 * 作为一类列出，可手动清（[CleanKind.APP_LOG]）。
 *
 * ### 三条硬约束（改动前先读）
 * 1. **删文件唯一入口**：过期清理走 [StorageCleaner.delete] 的单点三闸，本类**不自己删**。
 * 2. **绝不在回调里回头调 `CaptureLog`**：本 object 就是 `CaptureLog.fileSink` 的实现方，
 *    自我回写会递归。故本类自身故障**只进 logcat**（裸 `Log.w`，刻意为之 —— 它是日志链路的
 *    最后一环，没有更下游可写）。
 * 3. **不阻塞调用线程**：`onCaptureEvent` 只做「投队列」，磁盘 IO 全在 `app-log-writer`
 *    守护线程里；队列满就丢（不阻塞、不抛）—— 日志绝不该拖慢 tun 读线程。
 *
 * 线程：`onCaptureEvent` 由任意线程调（各模块照常调 `CaptureLog.i/w/e`）；`loop` 单写线程。
 * 进程级单例（`object`），只持 `applicationContext` ⇒ 无泄漏。
 */
object AppLog {

    /** 子目录名（与 `StorageCleaner.APP_LOG_DIR` 同值；两边各自持有以免互相触发类初始化）。 */
    const val DIR_NAME = "applog"

    /** 文件名前缀 / 后缀（与 `StorageCleaner.APP_LOG_NAME` 的规约同值）。 */
    const val PREFIX = "app-"
    const val SUFFIX = ".txt"

    /** 保留天数（含今天）。设置页文案与清理策略都从这里读，不写第二份。 */
    const val KEEP_DAYS = 7

    private const val TAG = "AppLog"
    private const val QUEUE_CAP = 4096
    private const val POLL_MS = 250L
    private const val TAG_WIDTH = 11

    private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val LINE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val lock = Any()
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAP)

    @Volatile private var enabled = false
    @Volatile private var appContext: Context? = null
    private var thread: Thread? = null
    private var writer: BufferedWriter? = null
    private var openDay: String = ""

    /** 当前是否开启（设置页开关的镜像；真源是 `SettingsStore.appLogEnabled`）。 */
    fun isEnabled(): Boolean = enabled

    /** 设置页入口：开 = [start]，关 = [stop]。**即改即生效**（无「下次生效」语义）。 */
    fun setEnabled(context: Context, on: Boolean) {
        if (on) start(context) else stop()
    }

    /**
     * 开启。幂等。
     *
     * 同时把本 object 接到 `CaptureLog.fileSink`（这是「整个 App」的唯一接法 —— 各模块
     * 本来就都调 `CaptureLog`，不必逐处改）。**开启之前发生的事件不追溯**：环里那 200 行
     * 是内存快照，不属「App 日志文件」口径。
     */
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (enabled) return
            appContext = app
            enabled = true
            CaptureLog.fileSink = ::onCaptureEvent
            if (thread?.isAlive != true) {
                thread = Thread({ loop() }, "app-log-writer").also {
                    it.isDaemon = true
                    it.start()
                }
            }
        }
        offer("App 日志已开启（按天分文件，保留最近 $KEEP_DAYS 天）")
    }

    /**
     * 关闭：置开关 + 投一行收尾。写线程把队列**排空后**再关文件并退出
     * （见 [loop]：`!enabled` 且队列空才退）⇒「关闭即停」不会丢最后几行。
     */
    fun stop() {
        synchronized(lock) {
            if (!enabled) return
            enabled = false
        }
        offer("App 日志已关闭")
    }

    /** `CaptureLog` 的事件回调：`level` 为 `I` / `W` / `E`。约束见 `CaptureLog.fileSink`。 */
    private fun onCaptureEvent(level: Char, tag: String, msg: String) {
        if (!enabled) return
        offer("$level  ${tag.padEnd(TAG_WIDTH)} $msg")
    }

    /** 打时间戳 → 投队列；队列满则丢弃（约束 3）。 */
    private fun offer(text: String) {
        // 时间戳在**字符串外**先算好：既少一层嵌套，也让「谁在用 LocalDateTime」这类静态核验看得见
        // （写在 `"${...}"` 里会被文本级核验当成字符串内容抹掉，属已知的核验假阳性来源）。
        val stamp = LocalDateTime.now().format(LINE_FMT)
        queue.offer("$stamp  $text")
    }

    // —— 写线程 ——

    private fun loop() {
        while (true) {
            val line = try {
                queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }
            if (line == null) {
                // 退出判定与清理**同一把锁**：否则与 start() 抢「谁是新写线程」，
                // 会出现「开关开着却没人消费队列」的静默失效。
                synchronized(lock) {
                    if (!enabled && queue.isEmpty()) {
                        closeQuietly()
                        thread = null
                        return
                    }
                }
                continue
            }
            try {
                append(line)
            } catch (e: Exception) {
                // 约束 2：自身故障只进 logcat，绝不回写 CaptureLog
                Log.w(TAG, "写 App 日志失败", e)
                closeQuietly()
            }
        }
        synchronized(lock) {
            closeQuietly()
            thread = null
        }
    }

    private fun append(line: String) {
        val ctx = appContext ?: return
        val day = LocalDate.now().format(DAY_FMT)
        if (writer == null || day != openDay) reopen(ctx, day)
        val w = writer ?: return
        w.write(line)
        w.newLine()
        // 每行 flush：进程被划掉时最多丢正在写的那一行。事件级频率，开销可接受。
        w.flush()
    }

    /** 打开当天文件（**追加写**，进程重启不截断）+ 写文件头 + 清过期。 */
    private fun reopen(ctx: Context, day: String) {
        closeQuietly()
        val ext = ctx.getExternalFilesDir(null) ?: return
        val dir = File(ext, DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "App 日志目录不可用：" + dir.absolutePath)
            return
        }
        val f = File(dir, PREFIX + day + SUFFIX)
        val out = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
        out.write("# 金鳞抽卡导出小助手 · App 日志（" + day + "）")
        out.newLine()
        out.write("# 仅存本机、不上传；按天分文件，保留最近 $KEEP_DAYS 天；可在「设置 → 数据清理」里手动清。")
        out.newLine()
        out.write("# 行格式：时间 级别 模块 内容（级别 I/W/E；与 logcat 同源，见 core/CaptureLog）")
        out.newLine()
        out.flush()
        writer = out
        openDay = day
        prune(ctx, ext)
    }

    /** 清掉早于「今天 −（[KEEP_DAYS] − 1）」的日志文件；**走 [StorageCleaner.delete] 单点三闸**。 */
    private fun prune(ctx: Context, ext: File) {
        val cutoff = LocalDate.now().minusDays((KEEP_DAYS - 1).toLong())
        val dir = File(ext, DIR_NAME)
        val old = (dir.listFiles() ?: return).filter { it.isFile }
            .filter { parseDay(it.name)?.isBefore(cutoff) == true }
            .map { CleanItem(file = it, bytes = it.length(), kind = CleanKind.APP_LOG) }
        if (old.isEmpty()) return
        val r = StorageCleaner(ctx.filesDir, ext).delete(old)
        if (r.deleted > 0 || r.failed > 0) {
            Log.i(TAG, "App 日志清理：删除 " + r.deleted + " 个过期文件，跳过 " + r.failed + " 个")
        }
    }

    private fun closeQuietly() {
        val w = writer ?: return
        writer = null
        openDay = ""
        try {
            w.flush()
            w.close()
        } catch (_: Exception) {
        }
    }

    /**
     * 从文件名解出日期（`app-<yyyyMMdd>.txt`）；不合规返回 `null`。
     * 与 `StorageCleaner.APP_LOG_NAME` 同一条判据（两边各自持有，改动须同改）。
     */
    internal fun parseDay(name: String): LocalDate? {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return null
        val body = name.substring(PREFIX.length, name.length - SUFFIX.length)
        return try {
            LocalDate.parse(body, DAY_FMT)
        } catch (_: Exception) {
            null
        }
    }
}
