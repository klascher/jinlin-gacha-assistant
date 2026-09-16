package com.jinlin.gacha.assistant.persistence

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 元数据接口的 HMAC-SHA256 签名（纯 JVM，零 Android 依赖）—— 与 PC
 * `gacha_exporter/utils/auth.py` 逐字对拍。
 *
 * 契约（09 设计稿 §3.4.2，实现时以 PC 代码为准复核过）：
 * - 签名串 = `key + str(timestamp)`，**无分隔符**（`auth.py:22`）；
 * - 摘要 = `HMAC-SHA256(secret, 签名串)` 的**小写十六进制**（`auth.py:23-27`）；
 * - `timestamp` 为**秒级** Unix 时间戳（PC `int(time.time())`）；
 * - 请求头 `X-Auth-Key` / `X-Auth-Timestamp` / `X-Auth-Signature`。
 *
 * ### ⚠️ 修正 09 设计稿 §3.4.2 示例代码的一处缺陷
 *
 * 设计稿示例写的是 `joinToString("") { "%02x".format(it) }`。其中 `it` 是 `Byte`，
 * 传入 `String.format` 前会**符号扩展为更宽的整型**——凡首字节 ≥ 0x80（即 Byte 为负）
 * 的字节都会输出成 `f` 打头的长串（形如 `ffffffb5`），而不是期望的两位 `b5`。
 * 实测 ts=1700000000 的摘要首字节恰为 0xB5，正好命中这一分支；约一半的签名会因此
 * 与 PC 不一致。正确写法必须显式屏蔽符号位：`it.toInt() and 0xff`。本实现采用后者。
 */
object HmacSign {

    /**
     * 计算签名。
     *
     * @param key 鉴权 key（PC：`example-key`）
     * @param secret 共享密钥（PC：`example-secret-not-real-0`）
     * @param timestampSec 秒级 Unix 时间戳（与请求头 `X-Auth-Timestamp` 用**同一个值**）
     * @return 64 位小写十六进制摘要
     */
    fun sign(key: String, secret: String, timestampSec: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$key$timestampSec".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 构造签名串（`key + str(ts)`，无分隔符）——独立成函数便于与 PC 对拍这一步。 */
    fun signingMessage(key: String, timestampSec: Long): String = "$key$timestampSec"
}
