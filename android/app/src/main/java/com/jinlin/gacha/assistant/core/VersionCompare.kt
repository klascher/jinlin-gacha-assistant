package com.jinlin.gacha.assistant.core

/**
 * 版本号解析与比较（纯 JVM、零依赖）—— 与 PC `gacha_exporter/utils/version.py` **逐条对齐**。
 *
 * 版本规则（PC `docs/打包与更新规划.md §11`）：
 * - 基础版号 `x.y.z`（数字段），缺段按 0 补齐；`0.2.2` 是某条线内的「最小」版本。
 * - 可选热修后缀 `-YYYYMMDDrcN`：8 位日期 + "rc" + 数字，如 `0.2.2-20260817rc1`。
 * - 比较：先比基础版号（`0.2.3` > 任意 `0.2.2-*`）→ 同基础，未带后缀的 < 带后缀的
 *   （`0.2.2` 最小，所以 `0.2.2-xxx` 必然比 `0.2.2` 新，能触发用户更新）→
 *   同基础带后缀再比日期（日期大的大）→ 同日期比 rc。
 * - **非法/空输入视为最旧**，保证「无法判断的版本不触发更新」（宁可漏提示，不可误提示）。
 *
 * 服务端 `android_version.json` 的 `latest_version` 必须与 `build.gradle.kts` 的
 * `versionName` 同源（发版基线校验见 `packaging/check_android_version.py`），
 * 否则要么永远提示更新、要么永不提示。
 */
object VersionCompare {

    /** x.y.z 可选 -8位日期 rc 数字（与 PC `_VERSION_RE` 同一形态）。 */
    private val VERSION_RE = Regex(
        """^(\d+)\.(\d+)\.(\d+)(?:-(\d{8})rc(\d+))?$"""
    )

    /**
     * 比较键 `(maj, minor, patch, tier, date, rc)`；tier=1 表示带热修后缀。
     * 非法/空输入解析为全 0（= 最旧，不触发更新），与 PC `_version_key` 一致。
     */
    private fun versionKey(v: String?): LongArray {
        if (v == null) return longArrayOf(0, 0, 0, 0, 0, 0)
        val m = VERSION_RE.matchEntire(v.trim()) ?: return longArrayOf(0, 0, 0, 0, 0, 0)
        val (maj, minor, patch, date, rc) = m.destructured
        // 日期/rc 缺失（无后缀）时 tier=0、date=rc=0，天然排在同基础所有带后缀版本之前
        return longArrayOf(
            maj.toLong(), minor.toLong(), patch.toLong(),
            if (date.isEmpty()) 0L else 1L,
            if (date.isEmpty()) 0L else date.toLong(),
            if (rc.isEmpty()) 0L else rc.toLong(),
        )
    }

    /** 比较两个版本号：a<b 返回 -1；a>b 返回 1；相等返回 0。热修后缀纳入比较。 */
    fun compare(a: String?, b: String?): Int {
        val ka = versionKey(a)
        val kb = versionKey(b)
        for (i in ka.indices) {
            if (ka[i] != kb[i]) return if (ka[i] < kb[i]) -1 else 1
        }
        return 0
    }

    /** [a] 是否严格新于 [b]（服务端 latest 是否新于端内当前版本）。 */
    fun isNewer(a: String?, b: String?): Boolean = compare(a, b) > 0
}
