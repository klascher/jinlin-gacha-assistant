package com.jinlin.gacha.assistant.core.dedup

/**
 * 极简 JSON 编解码（纯 JVM、零依赖）—— 供
 * [com.jinlin.gacha.assistant.persistence.HistoryStore] 读写与 PC 逐字一致的 history JSON。
 *
 * **为什么不用 `org.json`**：它是 android.jar 的桩实现，`testDebugUnitTest`（纯 JVM 单测）
 * 下不可用，会破坏「HistoryStore 可单测」这条约束；引第三方 JSON 库又与项目「零新依赖」
 * 的取向相悖。本实现只覆盖历史 payload 所需的 JSON 子集（对象 / 数组 / 字符串 / 整数 /
 * 浮点 / 布尔 / null），足以读 PC `json.dump(..., ensure_ascii=False, indent=2)` 写出的文件
 * （含嵌套、`\uXXXX` 转义与缩进空白）。
 *
 * 映射约定：object → `LinkedHashMap<String, Any?>`、array → `ArrayList<Any?>`、
 * 整数 → [Long]、浮点 → [Double]、字符串 → [String]、true/false → [Boolean]、null → null。
 */
object MiniJson {

    /** 解析失败（文本非法）。 */
    class JsonError(message: String) : Exception(message)

    private const val HEX = "0123456789abcdef"

    // —— 编码 ——

    /** 编码为单行 JSON 文本。 */
    fun encode(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    /**
     * 编码为缩进美化的 JSON 文本（对齐 PC `indent=2` 的可读性）。
     *
     * 仅供人读 / 落盘；**判等请用结构比较**（本类解码出的对象与重新构造的 payload 直接
     * `==` 即可，格式化差异不影响），见 `HistoryStore` 的内容幂等闸。
     */
    fun encodePretty(value: Any?, indent: Int = 2): String {
        val sb = StringBuilder()
        writePretty(sb, value, 0, indent)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int -> sb.append(v.toString())
            is Long -> sb.append(v.toString())
            is Double -> writeDouble(sb, v)
            is Float -> writeDouble(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writePretty(sb: StringBuilder, v: Any?, level: Int, indent: Int) {
        val pad = { n: Int -> " ".repeat(n * indent) }
        when (v) {
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(pad(level + 1))
                    writeString(sb, k.toString())
                    sb.append(": ")
                    writePretty(sb, value, level + 1, indent)
                }
                sb.append('\n').append(pad(level)).append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(",\n")
                    sb.append(pad(level + 1))
                    writePretty(sb, item, level + 1, indent)
                }
                sb.append('\n').append(pad(level)).append(']')
            }
            else -> write(sb, v)
        }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) {
            sb.append("null") // JSON 无 NaN/Infinity，降级为 null（历史 payload 不会出现）
            return
        }
        if (d == d.toLong().toDouble()) sb.append(d.toLong().toString()) else sb.append(d.toString())
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') writeUnicodeEscape(sb, c) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private fun writeUnicodeEscape(sb: StringBuilder, c: Char) {
        sb.append("\\u")
        sb.append(HEX[(c.code shr 12) and 0xf])
        sb.append(HEX[(c.code shr 8) and 0xf])
        sb.append(HEX[(c.code shr 4) and 0xf])
        sb.append(HEX[c.code and 0xf])
    }

    // —— 解码 ——

    /** 解析 JSON 文本；允许首尾空白，尾部有多余内容即报错（防截断/串接）。 */
    fun decode(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw JsonError("JSON 尾部有多余内容（位置 ${p.pos}）")
        return v
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (atEnd()) throw JsonError("JSON 意外结束")
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expectLiteral("true"); true }
                'f' -> { expectLiteral("false"); false }
                'n' -> { expectLiteral("null"); null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                map[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw JsonError("对象内期望 ',' 或 '}'（位置 $pos）")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw JsonError("数组内期望 ',' 或 ']'（位置 $pos）")
                }
            }
        }

        private fun parseString(): String {
            skipWs()
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonError("字符串未闭合")
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonError("转义未完成")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonError("\\u 转义不完整")
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonError("\\u 转义非法: $hex")
                                sb.append(code.toChar())
                            }
                            else -> throw JsonError("未知转义 \\$e（位置 $pos）")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && s[pos].isDigit()) pos++
            var isDouble = false
            if (!atEnd() && s[pos] == '.') {
                isDouble = true
                pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (!atEnd() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            val text = s.substring(start, pos)
            if (text.isEmpty() || text == "-") throw JsonError("非法数字（位置 $start）")
            return if (isDouble) {
                text.toDoubleOrNull() ?: throw JsonError("非法数字: $text")
            } else {
                text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw JsonError("非法数字: $text")
            }
        }

        private fun expectLiteral(lit: String) {
            if (pos + lit.length > s.length || s.substring(pos, pos + lit.length) != lit) {
                throw JsonError("期望 $lit（位置 $pos）")
            }
            pos += lit.length
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else s[pos]

        private fun expect(c: Char) {
            if (atEnd() || s[pos] != c) throw JsonError("期望 '$c'（位置 $pos）")
            pos++
        }
    }
}
