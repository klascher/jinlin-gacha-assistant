package com.jinlin.gacha.assistant.core

/**
 * 更新决策（**纯函数**，零依赖 ⇒ 纯 JVM 可测）—— 语义逐条对齐 PC
 * `gacha_exporter/__main__.py` 的 `_maybe_check_update`（§15 §3-C：「三态全实现，语义照搬 PC」）。
 *
 * PC 流程（同一判据顺序）：
 * 1. `latest` 不新于当前 → 解除强更状态、什么都不做；
 * 2. `silent` → 不弹窗、无横幅（仅设置页「检查更新」可见）；
 * 3. `force` → 弹窗不可关 + 禁用「开始抓包」（每次启动都弹，不看 acknowledged）；
 *    **安卓新增死锁守卫**：`ready=false`（APK 还没传上 OSS）⇒ 降级为 `prompt` 行为
 *    （弹窗可关、不拦抓包），否则用户被拦住又下不到包 = 全员卡死；
 * 4. 其余（`prompt` / 未识别值）→ `latest != acknowledged` 才弹（同版本不再打扰）+ 横幅常驻。
 *
 * 决策与拉取/持久化彻底分离：本层只吃值、吐决策；网络在 `VersionClient`，
 * `acknowledged` 存取在 `SettingsStore`，弹窗/横幅/置灰是 UI 层的事。
 */
object UpdateGate {

    /**
     * 决策结果。UI 按子类型渲染：
     * - 横幅显示条件：**非 [UpToDate] 且非 [Silent]**（`prompt`/`force` 常驻到版本追平）；
     * - 弹窗显示条件：[Prompt.showDialog] 或 [ForceBlocking]（`Prompt` 场景 `ready=false` 时按钮「新版准备中」置灰）；
     * - 禁用「开始抓包」条件：**仅 [ForceBlocking]**（历史/记录/导出/设置全部保留，PC 同款）。
     */
    sealed interface Decision {
        /** 无新版（含 latest 判不出 / 已追平）：无横幅、无弹窗、解除抓包禁用。 */
        data object UpToDate : Decision

        /** `silent`：设置页手动「检查更新」可见，不弹窗、无横幅。 */
        data object Silent : Decision

        /**
         * `prompt`（以及 `force`+`ready=false` 的降级形态）：横幅常驻；
         * 弹窗仅当启动后尚未确认过该版本（`showDialog`）。
         * [ready] 透传给弹窗按钮（false = 「新版准备中」置灰）。
         */
        data class Prompt(val showDialog: Boolean, val ready: Boolean) : Decision

        /** `force` 且包已就绪：每次启动弹不可关弹窗 + 禁用「开始抓包」+ 横幅常驻。 */
        data object ForceBlocking : Decision
    }

    /**
     * @param latestVersion       服务端 `latest_version`（`null`/非法 = 判不出 → 不更新）
     * @param currentVersion      端内当前 `versionName`
     * @param updateType          服务端 `update_type`（`null`/空/未识别 → 按 `prompt`，PC 同款兜底）
     * @param ready               服务端 `ready`（OSS 是否已传包；`false` 时 force 降级）
     * @param acknowledgedVersion 端内已确认过的版本（`SettingsStore.acknowledgedVersion`）
     */
    fun decide(
        latestVersion: String?,
        currentVersion: String,
        updateType: String?,
        ready: Boolean,
        acknowledgedVersion: String?,
    ): Decision {
        // 1. 不新于当前（含 latest 判不出）→ 无更新；顺带解除此前的强更拒绝（PC 同款清场）
        if (!VersionCompare.isNewer(latestVersion, currentVersion)) return Decision.UpToDate
        return when (updateType) {
            "silent" -> Decision.Silent
            "force" ->
                if (ready) Decision.ForceBlocking
                // 死锁守卫：包没传上去就不拦抓包，降级为 prompt 行为（弹窗提示「新版准备中」）
                else Decision.Prompt(showDialog = latestVersion != acknowledgedVersion, ready = false)
            // prompt / 空值 / 未识别值 → 一律按 prompt（宁少打扰，不误强拦）
            else -> Decision.Prompt(showDialog = latestVersion != acknowledgedVersion, ready = ready)
        }
    }
}
