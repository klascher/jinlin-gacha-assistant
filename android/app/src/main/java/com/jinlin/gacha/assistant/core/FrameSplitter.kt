package com.jinlin.gacha.assistant.core

/**
 * 帧切分层 —— 逐字复刻 PC `gacha_exporter/capture/engine.py::extract_frames`。
 *
 * 按大端 4 字节长度头切帧；失步重对齐按「长度合理 + 已知信封」一次跳到下一个结构
 * 自洽位置（结构化，2026-08 重构）。与 PC 保持一致，这是 Kotlin 平移正确性的根基
 * （阶段 1 验收「与 PC 管线逐帧 byte 对等」的对拍对象）。
 */
object FrameSplitter {

    const val DEFAULT_MIN_FRAME_LEN = 8     // 镜像 PC CaptureConfig.min_frame_len
    const val DEFAULT_MAX_FRAME_LEN = 50000 // 镜像 PC CaptureConfig.max_frame_len

    /**
     * 从缓冲区按长度头切帧。
     * @return (完整帧列表, 余留未成帧字节)
     */
    fun extract(
        data: ByteArray,
        direction: Direction,
        minLen: Int = DEFAULT_MIN_FRAME_LEN,
        maxLen: Int = DEFAULT_MAX_FRAME_LEN,
    ): Pair<List<Frame>, ByteArray> {
        val frames = mutableListOf<Frame>()
        var i = 0
        val n = data.size

        while (n - i >= 4) {
            // 长度头是**无符号** 32 位：readBE32 返回 0..2^32-1 的 Long。
            // 切忌先 .toInt()——>=0x80000000 会变成负数，令推进量变成 0 或负：
            // length==-4 时 i += 0，循环条件不变 => 死循环（切帧线程永久挂起、无日志）。
            // 因此这里全程用 Long 判定，只在确认落入 [minLen, maxLen] 后才转 Int。
            val lengthU = FrameSchema.readBE32(data, i)
            if (lengthU < minLen.toLong() || lengthU > maxLen.toLong()) {
                // 越界：整帧在则整帧跳过（合法但超短/超长，如 C->S 9B 心跳、S->C 8B
                // 被拒响应落此分支时流仍对齐）；半包/真错位才重对齐。
                // lengthU 无符号恒 >= 0，故 4 + lengthU >= 4，本分支 i 至少前进 4。
                if (4 + lengthU <= n - i) {
                    i += (4 + lengthU).toInt()
                    continue
                }
                // 结构化重对齐：跳到下一个「长度合理 + 信封自洽」偏移；无候选则保留
                // 缓冲尾部 3 字节等更多（长度头可能跨包分裂重组成 4B）
                var resyncAt = -1
                run {
                    for (p in 1 until n - i) {
                        if (frameStartPlausible(data, i + p, n, minLen, maxLen, direction)) {
                            resyncAt = p
                            return@run
                        }
                    }
                }
                if (resyncAt == -1) {
                    val keep = minOf(3, n - i)
                    i = if (keep > 0) n - keep else n
                    break
                }
                i += resyncAt
                continue
            }
            // 已确认 lengthU 落在 [minLen, maxLen]（<= 50000），转 Int 安全
            val length = lengthU.toInt()
            if (n - i < 4 + length) break // 半包，等更多字节
            val body = data.copyOfRange(i + 4, i + 4 + length)
            val header = FrameSchema.parseHeader(body, direction == Direction.SERVER_TO_CLIENT)
            frames.add(Frame(body, header, direction == Direction.SERVER_TO_CLIENT))
            i += 4 + length
        }
        return Pair(frames, data.copyOfRange(i, n))
    }

    /** data[pos:] 是否像合法帧起点（长度合理 + 已知信封自洽）；镜像 PC _frame_start_plausible。 */
    private fun frameStartPlausible(
        data: ByteArray,
        pos: Int,
        n: Int,
        minLen: Int,
        maxLen: Int,
        direction: Direction,
    ): Boolean {
        if (pos + 4 > n) return false
        // 同 extract()：长度头按无符号 32 位判定，不转 Int，避免大值变负
        val lengthU = FrameSchema.readBE32(data, pos)
        if (lengthU < minLen.toLong() || lengthU > maxLen.toLong()) return false
        val body = data.copyOfRange(pos + 4, n) // 仅取可用字节
        if (FrameSchema.envelopePlausible(body, direction)) return true
        // 整帧已在缓冲但信封不符 => 真不是帧起点；半包且信封字节未到齐 => 暂定可行
        val avail = n - (pos + 4)
        return 4 + lengthU > n - pos && avail < 9
    }
}