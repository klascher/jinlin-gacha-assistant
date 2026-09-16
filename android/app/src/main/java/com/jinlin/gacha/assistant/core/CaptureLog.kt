package com.jinlin.gacha.assistant.core

import android.util.Log
import java.util.Calendar
import java.util.Locale

/**
 * App 内观测日志缓冲（对应 `mobile/docs/07-App观测面板与录包入口设计.md` §3.2）。
 *
 * 进程级单例：每个事件「先 logcat、再入内存环形缓冲」双写，供 MainActivity 的日志区
 * 轮询展示。写方含 tun 读线程、各 socket 读线程、reassembly worker、录包写线程 →
 * 内部全部 synchronized。
 *
 * 上限 [MAX_LINES] 行，超出丢最旧。绝不在每包路径打日志——所有打点均为「事件一次性」或
 * 「周期汇总」。
 *
 * 每行统一加「HH:mm:ss + tag」前缀，与 logcat 自带的时间/tag 对齐：缺时间戳则 L7 这类
 * 周期汇总行在 App 内只是一堆无先后之分的重复文本，缺 tag 则多模块混排分不清来源。
 */
object CaptureLog {

    private const val MAX_LINES = 200
    private const val TAG_WIDTH = 11

    private val buffer = ArrayDeque<String>()
    private var version = 0L

    /** 仅在本 object 的 synchronized 块内访问，故无需额外同步。 */
    private val stampCal = Calendar.getInstance()

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append(tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append(tag, msg)
    }

    /**
     * 带异常的告警：logcat 保留完整堆栈，App 内折成一行（异常类型 + message），
     * 避免长堆栈撑爆 200 行缓冲、把前面的关键事件挤掉。
     */
    fun w(tag: String, msg: String, t: Throwable) {
        Log.w(tag, msg, t)
        append(tag, "$msg（${t.javaClass.simpleName}: ${t.message}）")
    }

    /**
     * 单调递增；Activity 只在 version 变化时重渲染，避免无谓 setText。
     * 每次 append/clear 都 +1，故「同 tag 同内容再次触发」也能被感知。
     */
    fun version(): Long = synchronized(this) { version }

    /** 加锁拷贝当前缓冲。 */
    fun snapshot(): List<String> = synchronized(this) { buffer.toList() }

    fun clear() {
        synchronized(this) {
            buffer.clear()
            version++
        }
    }

    private fun append(tag: String, msg: String) {
        synchronized(this) {
            buffer.addLast(stamp(tag, msg))
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            version++
        }
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