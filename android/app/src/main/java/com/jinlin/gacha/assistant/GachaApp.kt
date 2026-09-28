package com.jinlin.gacha.assistant

import android.app.Application
import com.jinlin.gacha.assistant.persistence.AppLog
import com.jinlin.gacha.assistant.persistence.SettingsStore

/**
 * App 进程入口（2026-09-22 新增；此前无自定义 `Application`，用的是系统默认）。
 *
 * 唯一职责：**进程起来时按设置恢复 App 级日志开关**（`SettingsStore.appLogEnabled` ⇒
 * `AppLog.start`）。为什么挂这里而不是 `MainActivity`：「开启即写」必须覆盖「用户开着日志、
 * App 被划掉后重开」这条路径，而重开可能**只有一个前台服务被拉起**、Activity 根本没进。
 *
 * 关（默认）时**什么都不做** —— 不建目录、不起线程、不接 `CaptureLog` 出口。
 * 运行中改开关由设置页直接调 `AppLog.setEnabled`（即改即生效），不经这里。
 */
class GachaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (SettingsStore.get(this).appLogEnabled) AppLog.start(this)
    }
}
