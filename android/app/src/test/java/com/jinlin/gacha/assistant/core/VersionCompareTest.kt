package com.jinlin.gacha.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VersionCompare] 单测 —— 用例**逐条对齐 PC `utils/version.py` 的测试口径**（§15 §4.2）：
 * 基础三段 → 同基础「无后缀 < 有后缀」→ 日期 → rc；非法/空 = 最旧。
 */
class VersionCompareTest {

    // —— 基础三段 ——

    @Test
    fun `patch dominates`() {
        assertTrue(VersionCompare.isNewer("0.2.3", "0.2.2"))
        assertFalse(VersionCompare.isNewer("0.2.2", "0.2.3"))
        assertEquals(0, VersionCompare.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun `minor and major dominate`() {
        assertTrue(VersionCompare.isNewer("0.3.0", "0.2.99"))
        assertTrue(VersionCompare.isNewer("1.0.0", "0.9.99"))
    }

    // —— 热修后缀（PC 规则核心：同基础，无后缀 < 有后缀）——

    @Test
    fun `suffixed is newer than same base unsuffixed`() {
        assertTrue(VersionCompare.isNewer("0.2.2-20260817rc1", "0.2.2"))
    }

    @Test
    fun `base dominates suffix`() {
        // 0.2.3 > 任意 0.2.2-*（基础版号先比）
        assertTrue(VersionCompare.isNewer("0.2.3", "0.2.2-20260817rc9"))
    }

    @Test
    fun `later date wins`() {
        assertTrue(VersionCompare.isNewer("0.2.2-20260818rc1", "0.2.2-20260817rc1"))
    }

    @Test
    fun `later rc wins`() {
        assertTrue(VersionCompare.isNewer("0.2.2-20260817rc2", "0.2.2-20260817rc1"))
    }

    // —— 非法 / 空输入 = 最旧（宁可漏提示，不可误提示）——

    @Test
    fun `illegal version is oldest and never triggers update`() {
        assertFalse(VersionCompare.isNewer("not-a-version", "0.1.0"))
        assertFalse(VersionCompare.isNewer("0.2", "0.1.0")) // 缺段不补齐，判非法
        assertFalse(VersionCompare.isNewer("0.2.2-20260817", "0.1.0")) // 后缀缺 rc
        assertFalse(VersionCompare.isNewer(null, "0.1.0"))
        assertFalse(VersionCompare.isNewer("", "0.1.0"))
    }

    @Test
    fun `equal strings compare equal`() {
        assertEquals(0, VersionCompare.compare("0.2.2-20260817rc1", "0.2.2-20260817rc1"))
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals(0, VersionCompare.compare(" 0.2.2 ", "0.2.2"))
    }
}
