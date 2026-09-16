package com.jinlin.gacha.assistant.ui

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.Scaffold
import com.jinlin.gacha.assistant.ui.capture.CaptureScreen
import com.jinlin.gacha.assistant.ui.screens.RecordsScreen
import com.jinlin.gacha.assistant.ui.screens.StatsScreen
import com.jinlin.gacha.assistant.ui.screens.SettingsScreen
import com.jinlin.gacha.assistant.ui.theme.LocalJinlinColors

/**
 * App 根脚手架：单 MainActivity 内底部 4 Tab（对齐 08-页面设计.md §2）。
 * 扁平 Tab 无返回栈需求，用「选中态 + when」切换，不引 navigation-compose（省依赖）。
 */
enum class MainTab(val label: String, val glyph: String) {
    Capture("抓包", "◎"),
    Records("记录", "◫"),
    Stats("统计", "◍"),
    Settings("设置", "⚙"),
}

@Composable
fun JinlinApp() {
    val colors = LocalJinlinColors.current
    var tab by rememberSaveable { mutableStateOf(MainTab.Capture) }

    Scaffold(
        containerColor = colors.bg,
        bottomBar = {
            NavigationBar(
                containerColor = colors.surface,
                contentColor = colors.onSurface,
            ) {
                MainTab.entries.forEach { t ->
                    val selected = tab == t
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = t },
                        icon = {
                            Text(
                                text = t.glyph,
                                color = if (selected) colors.gold else colors.onSurfaceDim,
                            )
                        },
                        label = {
                            Text(
                                text = t.label,
                                color = if (selected) colors.gold else colors.onSurfaceDim,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                MainTab.Capture -> CaptureScreen()
                MainTab.Records -> RecordsScreen()
                MainTab.Stats -> StatsScreen()
                MainTab.Settings -> SettingsScreen()
            }
        }
    }
}