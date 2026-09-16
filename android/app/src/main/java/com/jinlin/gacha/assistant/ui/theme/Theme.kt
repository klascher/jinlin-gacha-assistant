package com.jinlin.gacha.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 夜色金主题（对齐 08-页面设计.md §1.2 / §8）。
 * 深浅各一套 ColorScheme，经 isSystemInDarkTheme() 切换；业务语义色（[UP]绿/[歪]红/星级/置灰）
 * 走 [JinlinColors]，深浅下各配一组值，页面不写死色板。
 */

// —— 深色 ——
private val DarkGold = Color(0xFFD4AF37)
private val DarkBg = Color(0xFF0E1116)
private val DarkSurface = Color(0xFF171B22)
private val DarkSurfaceVariant = Color(0xFF202634)
private val DarkDivider = Color(0xFF262D3A)
private val DarkText = Color(0xFFE8EBF0)
private val DarkDim = Color(0xFF9AA3AF)

// —— 浅色 ——
private val LightGold = Color(0xFFA07D1C)
private val LightBg = Color(0xFFF5F6F8)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFEEF1F6)
private val LightDivider = Color(0xFFE3E7EE)
private val LightText = Color(0xFF1C222B)
private val LightDim = Color(0xFF5F6B7A)

// 业务语义色（深浅通用色相，仅亮度微调）
val ColorUpGreen: Color get() = Color(0xFF2E8B57)          // [UP]
val ColorWarpedRed: Color get() = Color(0xFFC0392B)        // [歪]
val GoldRarity: Color get() = Color(0xFFD4AF37)            // 六星
val PurpleRarity: Color get() = Color(0xFFA06FD9)          // 五星
val BlueRarity: Color get() = Color(0xFF3D7EDB)            // 四星
val GrayRarity: Color get() = Color(0xFF9AA4B0)            // 三星

/** 深浅下语义色集合：桌面 UI 用它取「当前主题下该用什么灰/点缀」，不直接读 isSystemInDarkTheme。 */
data class JinlinColors(
    val gold: Color,
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val divider: Color,
    val onSurface: Color,
    val onSurfaceDim: Color,
    val onSurfaceMuted: Color,
)

private val DarkColors = JinlinColors(
    gold = DarkGold, bg = DarkBg, surface = DarkSurface, surfaceVariant = DarkSurfaceVariant,
    divider = DarkDivider, onSurface = DarkText, onSurfaceDim = DarkDim,
    onSurfaceMuted = Color(0xFF6B7480),
)
private val LightColors = JinlinColors(
    gold = LightGold, bg = LightBg, surface = LightSurface, surfaceVariant = LightSurfaceVariant,
    divider = LightDivider, onSurface = LightText, onSurfaceDim = LightDim,
    onSurfaceMuted = Color(0xFF9AA4B0),
)

private val DarkScheme = darkColorScheme(
    primary = DarkGold,
    onPrimary = Color(0xFF0E1116),
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkDim,
    outline = DarkDivider,
)
private val LightScheme = lightColorScheme(
    primary = LightGold,
    onPrimary = Color.White,
    background = LightBg,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightDim,
    outline = LightDivider,
)

/** 全局主题：深浅随系统，业务语义色经 [LocalJinlinColors] 提供。 */
data object JinlinTheme {
    val current: JinlinColors
        @Composable get() = LocalJinlinColors.current
}

val LocalJinlinColors = androidx.compose.runtime.staticCompositionLocalOf<JinlinColors> { DarkColors }

@Composable
fun JinlinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val semantic = if (darkTheme) DarkColors else LightColors
    androidx.compose.runtime.CompositionLocalProvider(LocalJinlinColors provides semantic) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}