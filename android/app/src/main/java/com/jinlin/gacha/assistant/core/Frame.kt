package com.jinlin.gacha.assistant.core

/**
 * 帧结构层 —— Kotlin 逐字镜像 PC `gacha_exporter/capture/frame_schema.py`。
 *
 * 帧 = [4B 大端 totalLength] + 帧体（长度头由 FrameSplitter 消费，本模块只看帧体）
 *   C->S 帧体 = [4B seq][2B 类型][2B extId][1B counter][protobuf 业务体]
 *   S->C 帧体 = [2B 类型][2B extId][2B result][1B seq回显][1B ack][protobuf 业务体]
 *
 * counter / seq回显 = seq % SEQ_MOD(127)。已知信封：抽卡 0x000c/0x0800、心跳 0x0001/0x0200。
 * 白名单仅用于失步重对齐评分（envelope_plausible）与业务分派；帧边界永远由 4B 长度头决定，
 * 未知消息类型照常按长度切帧放行（不解析）。
 */
enum class Direction { SERVER_TO_CLIENT, CLIENT_TO_SERVER }

object MessageType {
    const val GACHA = 0x000C      // 抽卡记录请求/响应
    const val HEARTBEAT = 0x0001  // 心跳（C->S 9B / S->C 15B）
}

object ExtId {
    const val GACHA = 0x0800
    const val HEARTBEAT = 0x0200
}

object ResultCode {
    const val OK = 0
    const val REJECTED = 142 // 0x008e：请求被限流拒绝（无业务体）
}

const val SEQ_MOD = 127

private val KNOWN_ENVELOPES = setOf(
    MessageType.GACHA to ExtId.GACHA,
    MessageType.HEARTBEAT to ExtId.HEARTBEAT,
)

/** 帧头解析结果（不含 4B 长度头与业务体）；known=False 表示类型/extId 不在白名单。 */
data class FrameHeader(
    val serverToClient: Boolean,
    val msgType: Int,
    val extId: Int,
    val known: Boolean,
    val payloadOffset: Int,
    val seq: Long = 0,      // C->S：完整 4B 大端 seq（心跳/抽卡共享计数器）
    val counter: Int = -1,  // C->S：seq % SEQ_MOD 回显（-1 表示 S->C 无此字段）
    val result: Int = -1,   // S->C：0 成功 / 142 被拒（-1 表示 C->S 无此字段）
    val ack: Int = -1,      // S->C：应答字节（语义未完全确认，仅透传）
    val seqEcho: Int = -1,  // S->C：请求 seq % SEQ_MOD 的回显
)

/** 切出的完整一帧：帧体 + 解析头（body 过短解析不出时 header 为 null，但帧仍合法）。 */
data class Frame(val body: ByteArray, val header: FrameHeader?, val serverToClient: Boolean)

object FrameSchema {

    /** 解析帧头；帧体过短/结构不自洽返回 null（与 PC parse_header 双分支一致）。 */
    fun parseHeader(body: ByteArray, serverToClient: Boolean): FrameHeader? {
        if (serverToClient) {
            // S->C：[类型2][extId2][result2][seq回显1][ack1][业务体]
            if (body.size < 8) return null
            val msgType = readBE16(body, 0)
            val extId = readBE16(body, 2)
            val result = readBE16(body, 4)
            return FrameHeader(
                serverToClient = true,
                msgType = msgType,
                extId = extId,
                known = msgType to extId in KNOWN_ENVELOPES,
                result = result,
                seqEcho = body[6].toInt() and 0xff,
                ack = body[7].toInt() and 0xff,
                payloadOffset = 8,
            )
        }
        // C->S：[seq4][类型2][extId2][counter1][业务体]
        if (body.size < 9) return null
        val seq = readBE32(body, 0)
        val msgType = readBE16(body, 4)
        val extId = readBE16(body, 6)
        val counter = body[8].toInt() and 0xff
        var known = msgType to extId in KNOWN_ENVELOPES
        if (known && counter != (seq % SEQ_MOD).toInt()) {
            // 已知类型但 counter 不自洽 -> 大概率不是帧边界（错位/脏数据）
            known = false
        }
        return FrameHeader(
            serverToClient = false,
            msgType = msgType,
            extId = extId,
            known = known,
            seq = seq,
            counter = counter,
            payloadOffset = 9,
        )
    }

    /** 帧体开头是否像指定方向的一个合法帧头（失步重对齐评分用，镜像 PC envelope_plausible）。 */
    fun envelopePlausible(body: ByteArray, direction: Direction): Boolean {
        val h = parseHeader(body, direction == Direction.SERVER_TO_CLIENT) ?: return false
        return h.known
    }

    fun readBE32(data: ByteArray, off: Int): Long =
        ((data[off].toLong() and 0xff) shl 24) or
            ((data[off + 1].toLong() and 0xff) shl 16) or
            ((data[off + 2].toLong() and 0xff) shl 8) or
            (data[off + 3].toLong() and 0xff)

    fun readBE16(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xff) shl 8) or (data[off + 1].toInt() and 0xff)
}