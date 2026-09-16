package com.jinlin.gacha.assistant.core.dedup

import com.jinlin.gacha.assistant.core.FrameSchema
import com.jinlin.gacha.assistant.core.GachaRecognizer
import com.jinlin.gacha.assistant.core.MessageType
import com.jinlin.gacha.assistant.core.ResultCode

/**
 * 切池/翻页帧编解码 —— Kotlin 逐字镜像 PC `gacha_exporter/analysis/pool_view.py` 的
 * `parse_pool_request` / `parse_pool_list_type` / `parse_pool_response`。
 *
 * 依据 `卡池切换分析/分析结论.md`（抓包样本逐字节核对）：切换卡池与翻页是同一种
 * C→S 请求帧，业务体 `08 <pool_id> 10 <offset> [18 <list_type>]`；响应业务体
 * `08 <offset回显> 10 <total>` + repeated 记录（field3）。
 *
 * 帧体布局（对齐 `core/Frame.kt`）：
 *   C→S = [4B seq][2B 类型][2B extId][1B counter][业务体]
 *   S→C = [2B 类型][2B extId][2B result][1B seq回显][1B ack][业务体]
 *
 * **field3(list_type) 是 H1 契约的关键**：同为 pool_id=0 的「全部卡池」与按契约类型
 * 聚合的汇总页（活动/遴选）只靠它区分；缺失时视图标识退化为裸 `"0"`（老协议行为，
 * 见 [ViewIdentity.makeView]）。
 *
 * 纯 JVM、零 Android 依赖，可在 junit 里对拍。
 */

/** 一条 C→S 切池/翻页请求：`(seq, pool_id, offset, list_type?)`。 */
data class PoolRequest(
    val seq: Long,
    val poolId: Int,
    val offset: Int,
    /** field3；协议未带时为 null（老协议）→ 视图标识退化为裸 pool_id。 */
    val listType: Int?,
)

/** 一条 S→C 切池/翻页响应：`(seq回显, offset, total, rejected)`。 */
data class PoolResponse(
    /** 请求 seq % [com.jinlin.gacha.assistant.core.SEQ_MOD] 的回显（配对键维度）。 */
    val seq: Int,
    val offset: Int,
    /** 服务器权威总抽数（field2）；rejected 时无意义（恒 0）。 */
    val total: Int,
    /** true = 该请求被限流拒绝（result=142 且 8 字节截断帧，无业务体）。 */
    val rejected: Boolean,
)

object PoolRequestCodec {

    /**
     * 解析一条 C→S 切池/翻页请求（镜像 `parse_pool_request`）。
     *
     * @param body C→S 帧体（不含 4B 长度头，含前 4 字节 seq）
     * @return 解析结果；非切池/翻页请求、信封未知、结构不符时为 null
     */
    fun parse(body: ByteArray): PoolRequest? {
        val header = FrameSchema.parseHeader(body, serverToClient = false) ?: return null
        if (!header.known || header.msgType != MessageType.GACHA) return null
        val tail = body.copyOfRange(header.payloadOffset, body.size)
        val parsed = parseTail(tail) ?: return null
        return PoolRequest(header.seq, parsed.first, parsed.second, parsed.third)
    }

    /**
     * 解析 C→S 请求的卡池列表类型（field3，标签 0x18，镜像 `parse_pool_list_type`）。
     *
     * 独立成函数：`parse` 的返回签名被调用方依赖，类型字段仅用于细化视图标识。
     *
     * @return listType；字段缺失或非切池/翻页请求时返回 null
     */
    fun parseListType(body: ByteArray): Int? {
        val header = FrameSchema.parseHeader(body, serverToClient = false) ?: return null
        if (!header.known || header.msgType != MessageType.GACHA) return null
        val tail = body.copyOfRange(header.payloadOffset, body.size)
        return parseTail(tail)?.third
    }

    /**
     * 解析请求业务体 `08 <pool> 10 <offset> [18 <list_type>]`。
     * Returns: `(poolId, offset, listType?)` 或 null（结构不符）。
     */
    private fun parseTail(tail: ByteArray): Triple<Int, Int, Int?>? {
        if (tail.size < 2 || (tail[0].toInt() and 0xff) != 0x08) return null
        val (poolId, afterPool) = GachaRecognizer.readVarint(tail, 1)
        if (afterPool >= tail.size || (tail[afterPool].toInt() and 0xff) != 0x10) return null
        val (offset, afterOffset) = GachaRecognizer.readVarint(tail, afterPool + 1)
        var listType: Int? = null
        if (afterOffset < tail.size && (tail[afterOffset].toInt() and 0xff) == 0x18) {
            val (lt, _) = GachaRecognizer.readVarint(tail, afterOffset + 1)
            listType = lt.toInt()
        }
        return Triple(poolId.toInt(), offset.toInt(), listType)
    }
}

object PoolResponseCodec {

    /**
     * 解析一条 S→C 切池/翻页响应（镜像 `parse_pool_response`）。
     *
     * result=142 且 8 字节截断帧 = 请求被限流拒绝（该次响应无数据，客户端随后自动重发）；
     * 此时 offset/total 恒 0，`rejected=true`。
     *
     * @param body S→C 帧体（不含 4B 长度头）
     * @return 解析结果；非抽卡信封、结构不符时为 null
     */
    fun parse(body: ByteArray): PoolResponse? {
        val header = FrameSchema.parseHeader(body, serverToClient = true) ?: return null
        if (!header.known || header.msgType != MessageType.GACHA) return null
        val seq = header.seqEcho
        val rejected = header.result == ResultCode.REJECTED && body.size == 8
        if (rejected) return PoolResponse(seq, 0, 0, true)
        // 正常响应：业务体 field1 标签 0x08 起，varint offset 回显；total 跟在
        // offset varint 之后的 0x10 标签处。offset 是 varint（深翻页 ≥130 为双字节），
        // 必须整段解码，不能按单字节读。
        val payload = body.copyOfRange(header.payloadOffset, body.size)
        if (payload.size < 4 || (payload[0].toInt() and 0xff) != 0x08) return null
        val (offset, afterOffset) = GachaRecognizer.readVarint(payload, 1)
        if (afterOffset >= payload.size || (payload[afterOffset].toInt() and 0xff) != 0x10) return null
        val (total, _) = GachaRecognizer.readVarint(payload, afterOffset + 1)
        return PoolResponse(seq, offset.toInt(), total.toInt(), false)
    }
}
