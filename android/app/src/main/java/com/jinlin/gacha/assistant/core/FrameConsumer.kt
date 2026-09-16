package com.jinlin.gacha.assistant.core

/**
 * 完整帧消费者 —— 阶段 2 parser/stats Kotlin 平移的输入口。
 * 阶段 1 具象用 [LogFrameConsumer]（logcat 打点）；M3 加帧 dump（对拍 PC 管线用）。
 */
interface FrameConsumer {
    fun onFrame(frame: Frame, direction: Direction)
}

/** M2 可观测实现：logcat 打点，验证「帧能切出、方向/信封判定正确」。 */
class LogFrameConsumer : FrameConsumer {

    private var serverFrames = 0L
    private var clientFrames = 0L
    private var lastLogged = 0L

    @Volatile
    var lastServerFrame: Frame? = null
        private set

    override fun onFrame(frame: Frame, direction: Direction) {
        if (direction == Direction.SERVER_TO_CLIENT) {
            serverFrames++
            lastServerFrame = frame
        } else {
            clientFrames++
        }
        lastLogged++
        // 打点阈值 32→8（07 设计 L6）：翻一次抽卡页约 20 帧，原阈值一行都看不到；写 CaptureLog 供 App 内观测
        if (serverFrames % 8 == 0L) {
            CaptureLog.i(
                "GachaFrame",
                "累计 S->C=${serverFrames} C->S=${clientFrames} " +
                    "最近=${frame.header?.let { "0x%04x/0x%04x".format(it.msgType, it.extId) } ?: "未知"} " +
                    "len=${frame.body.size}"
            )
        }
    }
}