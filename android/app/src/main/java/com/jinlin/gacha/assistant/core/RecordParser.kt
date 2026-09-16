package com.jinlin.gacha.assistant.core

/**
 * 抽卡记录解析 —— Kotlin 逐字镜像 PC `gacha_exporter/analysis/parser.py::extract_records`
 * + `analysis/pool_view.py::parse_pool_response`（仅取 offset 回显）。
 *
 * 消费 M2 切出的 S→C 完整帧体（不含 4B 长度头，含 8B 固定头），把业务体里 repeated
 * field3 的子记录（field1=pool / field2=item / field3=timestamp）解码成 [GachaRecord]。
 * 非抽卡帧（心跳）/未知类型/被拒（result=142）直接返回空列表，与 PC 语义一致。
 *
 * `positionInBatch` 用响应 field1 的 offset 回显归一化为「视图内绝对序号」（0=最新一条），
 * 深翻页 offset≥130 是双字节 varint，必须整段 varint 解码（不能按单字节读）。
 *
 * **本批只切分，不去重**：同一次十连被多个视图冲到会多次解出并全部保留（重复属预期，
 * 待后续批接去重账本统一处理）。纯 JVM、零 Android 依赖，可在 junit 里对拍。
 */
object RecordParser {

    /**
     * 解析一帧 S→C 抽卡响应，按帧内顺序提取记录。
     * @param body 完整帧体（不含 4B 长度头）
     * @param batchSeq 请求 seq（仅 offset 回显缺失的回退路径写入 batchSeq 字段）
     * @return GachaRecord 列表（按帧内顺序）；非抽卡/被拒/结构不符为空
     */
    fun extractRecords(body: ByteArray, batchSeq: Int = 0): List<GachaRecord> {
        val header = FrameSchema.parseHeader(body, serverToClient = true) ?: return emptyList()
        if (!header.known || header.msgType != MessageType.GACHA) return emptyList()
        if (header.result != ResultCode.OK) return emptyList()

        val pageOffset = parsePoolOffset(body, header.payloadOffset)
        val payload = body.copyOfRange(header.payloadOffset, body.size)

        val records = mutableListOf<GachaRecord>()
        var i = 0
        val n = payload.size
        while (i < n) {
            val tag = payload[i].toInt() and 0xff
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            i++

            if (fieldNum == 3 && wireType == 2) {
                // repeated 记录字段：长度前缀 + 子消息
                val (len, after) = GachaRecognizer.readVarint(payload, i)
                i = after
                if (len < 0 || i + len > n) break  // 长度非法/越界：已解出的保留，剩余忽略
                parseRecordFields(payload, i, (i + len).toInt())?.let { (poolId, itemId, ts) ->
                    records.add(
                        GachaRecord(
                            poolId = poolId,
                            itemId = itemId,
                            timestamp = ts,
                            batchSeq = if (pageOffset != null) 0 else batchSeq,
                            positionInBatch = if (pageOffset != null) pageOffset + records.size else records.size,
                        )
                    )
                }
                i += len.toInt()
            } else if (wireType == 0) {
                // field1(offset 回显)/field2(total) 等已知 varint 字段：跳过
                val (_, after) = GachaRecognizer.readVarint(payload, i)
                i = after
            } else if (wireType == 2) {
                // 其他长度前缀字段：跳过内容
                val (len, after) = GachaRecognizer.readVarint(payload, i)
                i = after
                i += len.toInt()
            } else {
                break  // 不认识的 wire type：停止解析（保留已提取记录，与 PC 一致）
            }
        }
        return records
    }

    /**
     * 解析响应业务体的 field1 offset 回显（镜像 `parse_pool_response` 的 offset 部分）。
     * 业务体 `[08 <offset varint> 10 <total varint> ...]`；`payload[after]==0x10`（field2=total
     * 标签）作有效门（不足则结构不符）。返回 null 表示无法确定 offset。
     */
    private fun parsePoolOffset(body: ByteArray, payloadOffset: Int): Int? {
        val payload = body.copyOfRange(payloadOffset, body.size)
        if (payload.size < 2) return null
        // offset 值 varint 从标签字节(0x08)之后的 payload[1] 起读（深翻页双字节）
        val (offset, after) = GachaRecognizer.readVarint(payload, 1)
        if (after >= payload.size || payload[after].toInt() and 0xff != 0x10) return null
        return offset.toInt()
    }

    /** 解一条记录子消息的三字段；缺失/结构不符返回 null（镜像 `_parse_record_fields`）。 */
    private fun parseRecordFields(data: ByteArray, from: Int, to: Int): Triple<String, String, Long>? {
        var poolId = ""
        var itemId = ""
        var timestamp = 0L
        var j = from
        while (j < to) {
            val tag = data[j].toInt() and 0xff
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            j++
            if (wireType != 0) break  // 非 varint 字段即止（PC 同语义）
            val (v, after) = GachaRecognizer.readVarint(data, j)
            j = after
            when (fieldNum) {
                1 -> poolId = v.toString()
                2 -> itemId = v.toString()
                3 -> timestamp = v
            }
        }
        // PC：if pool_id and item_id and timestamp —— 三者非空才命中
        return if (poolId.isNotEmpty() && itemId.isNotEmpty() && timestamp != 0L) {
            Triple(poolId, itemId, timestamp)
        } else {
            null
        }
    }
}