package com.jinlin.gacha.assistant.core

import android.util.Log
import java.util.Calendar
import java.util.Locale

/**
 * App 内观测日志缓冲（对应 `mobile/docs/07-App观测面板与录包入口设计.md` §3.2）。
 *
 * 进程级单例：每个事件「先 logcat、再入内存环形缓冲」双写。
 *
 * ⚠️ **2026-09-18 就地更正**：原句写「供 MainActivity 的日志区轮询展示」，但**该日志区已随
 * View 版 UI 一并移除** —— `MainActivity` 现只剩 Compose 壳（`setContent { JinlinApp() }`），
 * 全仓**已无 `snapshot()` 的调用者** ⇒ 内存环**当前没有读者**，logcat 是唯一即时出口；
 * 环的**唯一预定消费者**是 U11 的 `diagnose` 目录下同名 .txt 落盘器（`10-去重模块设计.md` §16.4）。
 * 写方含 tun 读线程、各 socket 读线程、reassembly worker、录包写线程 →
 * 内部全部 synchronized。
 *
 * 上限 [MAX_LINES] 行，超出丢最旧。绝不在每包路径打日志——所有打点均为「事件一次性」或
 * 「周期汇总」。
 *
 * 每行统一加「HH:mm:ss + tag」前缀，与 logcat 自带的时间/tag 对齐：缺时间戳则 L7 这类
 * 周期汇总行在 App 内只是一堆无先后之分的重复文本，缺 tag 则多模块混排分不清来源。
 *
 * 级别：对外暴露 `i` / `w` / `e`（各含「带异常」重载）。logcat 侧级别**如实保留**；
 * 内存环**不存级别**（行格式固定 `HH:mm:ss tag msg`，见 [stamp]）—— 故 `.txt` 报告里逐行
 * 看不出 info/error，需要级别时看 logcat。**App 日志文件（[fileSink]）另存级别**。
 *
 * 📌 2026-09-22：`GachaVpnService` 最后 6 处裸 `Log.*` 已并入本 object（U11 余项收口）⇒
 * 该文件的日志与诊断包 `.txt` 现在**同一条链路**，不再有只进 logcat 的打点。
 *
 * 📌 2026-09-22（同日第二批）：新增 [fileSink] —— 把本 object 收到的每条事件**再抄一份**到
 * **App 日志文件**（`persistence/AppLog`）。三处出口的分工：logcat = 实时看；
 * 内存环 = 诊断包 `.txt` 的快照（**只在抓包会话里**随「抓诊断」落盘）；
 * 文件 = **会话之外也写**（开抓包之前 / 停抓之后都有），由设置页开关控制。三者同源。
 */
object CaptureLog {

    private const val MAX_LINES = 200
    private const val TAG_WIDTH = 11

    private val buffer = ArrayDeque<String>()
    private var version = 0L

    /** 仅在本 object 的 synchronized 块内访问，故无需额外同步。 */
    private val stampCal = Calendar.getInstance()

    /**
     * 文件级落盘出口（**App 级日志**，2026-09-22 落码）。`null` = 未接（默认；零开销）。
     *
     * 由 `persistence/AppLog` 在开关打开时接上（见其 `start`）—— **会话之外也写**，
     * 这正是它与「抓包时才有的」诊断包 `.txt` 的区别。级别**如实传入**（`I` / `W` / `E`：
     * 环不存级别，文件存）。
     *
     * 为什么做成钩子、而不让本 object 自己写文件：落盘要 `Context`、目录与保留策略，
     * 属**持久化层**；`core` 反向依赖 `persistence` 会把两层搅在一起。同一手法见
     * `DiagnoseDumper.reportSource` / `onRecordStateChanged`。
     *
     * ⚠️ **实现方不得在回调里回头调本 object** —— 那会自我递归。
     */
    var fileSink: ((level: Char, tag: String, msg: String) -> Unit)? = null

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        emit('I', tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        emit('W', tag, msg)
    }

    /**
     * 带异常的告警：logcat 保留完整堆栈，环内折成一行（异常类型 + message），
     * 避免长堆栈撑爆 200 行缓冲、把前面的关键事件挤掉。文件侧同折法（见 [fold]）。
     */
    fun w(tag: String, msg: String, t: Throwable) {
        Log.w(tag, msg, t)
        emit('W', tag, fold(msg, t))
    }

    /** 错误级：logcat 取 `Log.e`，环内与 [w] 同一形态（**环不存级别**，级别只在 logcat / 文件侧）。 */
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        emit('E', tag, msg)
    }

    /** 错误级 + 异常：logcat 保留完整堆栈，环内折成一行（同 [w] 的三参重载）。 */
    fun e(tag: String, msg: String, t: Throwable) {
        Log.e(tag, msg, t)
        emit('E', tag, fold(msg, t))
    }

    /**
     * 单调递增；Activity 只在 version 变化时重渲染，避免无谓 setText。
     * 每次 emit/clear 都 +1，故「同 tag 同内容再次触发」也能被感知。
     */
    fun version(): Long = synchronized(this) { version }

    /** 加锁拷贝当前缓冲。U11 的 `.txt` 落盘器是它的**唯一读者**（见类注释）。 */
    fun snapshot(): List<String> = synchronized(this) { buffer.toList() }

    /**
     * 环形缓冲的行数上限。
     *
     * 暴露出来只为一件小事：`.txt` 诊断报告里要写明「日志最多 N 行」——
     * 从这一处读，改 [MAX_LINES] 时报告文案自动跟着走，不会再出现
     * 「文档写 200、实现改成 500」这种漂移。
     */
    fun capacity(): Int = MAX_LINES

    fun clear() {
        synchronized(this) {
            buffer.clear()
            version++
        }
    }

    /** 异常在环内 / 文件里都折成一行（logcat 侧仍留完整堆栈）。 */
    private fun fold(msg: String, t: Throwable): String =
        "$msg（${t.javaClass.simpleName}: ${t.message}）"

    /**
     * 唯一出口：入环（带 `HH:mm:ss` 戳）+ 抄给 [fileSink]。
     *
     * 文件侧刻意放在 `synchronized` **之外**：那是一次跨线程队列投递，不该占住 ring 的锁
     * 去卡其它写者（tun 读线程也走这里）。
     */
    private fun emit(level: Char, tag: String, msg: String) {
        synchronized(this) {
            buffer.addLast(stamp(tag, msg))
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            version++
        }
        fileSink?.invoke(level, tag, msg)
    }

    /** `HH:mm:ss tag       msg`，tag 右补齐到 [TAG_WIDTH] 便于纵向对齐（超长不截断）。 */
    private fun stamp(tag: String, msg: String): String {
        stampCal.timeInMillis = System.currentTimeMillis()
        val h = stampCal.get(Calendar.HOUR_OF_DAY)
        val m = stampCal.get(Calendar.MINUTE)
        val s = stampCal.get(Calendar.SECOND)
        return "%02d:%02d:%02d %s %s".format(Locale.US, h, m, s, tag.padEnd(TAG_WIDTH), msg)
    }
}