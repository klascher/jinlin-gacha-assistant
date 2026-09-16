package com.jinlin.gacha.assistant.core

/**
 * 抽卡特征判定 —— Kotlin 逐字镜像 PC `gacha_exporter/analysis/parser.py` 的 `looks_like_gacha`
 * 判定（针对 `extract_records` 的「能否命中」子集）。
 *
 * 用于 M2 特征锁定：判定一帧（不含 4B 长度头）是否像「成功返回、且含 ≥1 条完整记录」的抽卡
 * 响应。只消费布尔判定，**不解析业务内容**（pool 名/时间戳留阶段 2 stats 平移）。
 *
 * 为与 PC 判定**逐字节等价**（M2/§4 对拍闸门的基础），必须完整复刻同一套记录解析——
 * 信封 GACHA + 结果 OK + 遍历业务体 repeated field3 子记录，每条记录内 field1/2/3(pool/
 * item/timestamp) 齐全且 timestamp≠0 才算命中（镜像 `_parse_record_fields` 的判据）。
 *
 * 参考 `mobile/docs/02-阶段1设计.md` §3。不引入任何第三方 GPL 源码。
 */
object GachaRecognizer {

    /**
     * 判定一帧是否像抽卡响应（镜像 PC `parser.looks_like_gacha`）。
     * @param frameBody 不含 4B 长度头的完整帧体。
     */
    fun looksLikeGacha(frameBody: ByteArray): Boolean {
        val h = FrameSchema.parseHeader(frameBody, serverToClient = true) ?: return false
        if (!h.known || h.msgType != MessageType.GACHA) return false
        if (h.result != ResultCode.OK) return false
        return recordCount(frameBody, h.payloadOffset) > 0
    }

    /**
     * 遍历业务体重叠的 repeated 记录字段，统计「三大字段齐全」条数（镜像 extract_records 的
     * 记录循环 + `_parse_record_fields` 判定）。
     */
    private fun recordCount(data: ByteArray, start: Int): Int {
        var i = start
        var count = 0
        val n = data.size
        while (i < n) {
            val tag = data[i].toInt() and 0xff
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            i++
            if (fieldNum != 3 || wireType != 2) {
                // 非记录字段：与 PC 一致只前移 1 字节，不按 wire_type 跳读（简化解析）
                continue
            }
            val (len, after) = readVarint(data, i)
            i = after
            // len < 0 的防护不可省：10 字节 varint 的 bit63 会置位，转 Long 后是负数，
            // 此时 `i += len` 会让 i 倒退，外层 while 永不退出（切帧线程直接挂死）。
            if (len < 0 || i + len > n) break  // 长度非法/越界：抽卡记录已解析，剩余忽略
            if (recordComplete(data, i, (i + len).toInt())) count++
            i += len.toInt()   // len 来自 readVarint（Long）；到此处已保证 i+len<=n，len 必在 Int 域内
        }
        return count
    }

    /** 解析一条记录内部字段；field1|2|3 齐全且 timestamp≠0 才算完整（镜像 `_parse_record_fields`）。 */
    private fun recordComplete(data: ByteArray, from: Int, to: Int): Boolean {
        var poolId = ""
        var itemId = ""
        var timestamp = 0L
        var j = from
        while (j < to) {
            val tag = data[j].toInt() and 0xff
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            j++
            if (wireType != 0) break            // 非 varint 字段即止（PC 同语义）
            val (v, after) = readVarint(data, j)
            j = after
            when (fieldNum) {
                1 -> poolId = v.toString()
                2 -> itemId = v.toString()
                3 -> timestamp = v
            }
        }
        // PC：if pool_id and item_id and timestamp —— pool/item 非空链、timestamp 非 0 才命中
        return poolId.isNotEmpty() && itemId.isNotEmpty() && timestamp != 0L
    }

    /** protobuf varint 最长 10 字节（70 位）；再长即非法。 */
    private const val MAX_VARINT_BYTES = 10

    /**
     * 读 protobuf varint；返回 (value, nextOffset)（镜像 `_read_varint`）。
     *
     * 原实现无字节上限：JVM 的 `shl` 对 shift 取模 64，shift ≥ 64 后高位**静默回绕**，
     * 读出来的是一个「看似合法」的错误长度。这里卡在 10 字节并停在已消费位置——
     * 必须保证 nextOffset > offset，否则调用方 `i += len` 可能原地不动而死循环。
     */
    fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < data.size) {
            if (i - offset >= MAX_VARINT_BYTES) break // 超长 varint：不是合法编码
            val byte = data[i].toInt() and 0xff
            result = result or ((byte.toLong() and 0x7f) shl shift)
            i++
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result to i
    }
}