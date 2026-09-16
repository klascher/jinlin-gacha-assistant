package com.jinlin.gacha.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jinlin.gacha.assistant.ui.JinlinApp
import com.jinlin.gacha.assistant.ui.theme.JinlinTheme

/**
 * 产品版 UI 的单 Activity 入口（对齐 08-页面设计.md §2：保持单 MainActivity）。
 *
 * 2.0 期：Compose 壳（4 Tab 骨架 + 深浅主题）。抓包/录包/权限协调在 2.1 期经
 * CaptureViewModel 接回 GachaVpnService；阶段 1 的抓包/VPN/切帧业务代码（core/locator/vpn）
 * 不动，仅 UI 层迁到 Compose。
 *
 * 注意：本文件原为 View 版（AppCompatActivity + activity_main.xml + Handler 轮询），
 * 已整段替换为 Compose；activity_main.xml 不再被引用，见 docs/08-页面设计.md §13 2.0 期。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JinlinTheme {
                JinlinApp()
            }
        }
    }
}