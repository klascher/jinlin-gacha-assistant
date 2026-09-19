package com.jinlin.gacha.assistant.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.jinlin.gacha.assistant.core.CaptureLog
import com.jinlin.gacha.assistant.persistence.SettingsStore

/**
 * 目标游戏**包名解析**（多渠道服支持）—— 2026-09-18 新增。
 *
 * ### 缘起（用户反馈）
 * 原实现把目标包名写死为常量 `GachaVpnService.TARGET_PACKAGE = com.bmystu.peng.gw`，且
 * `AndroidManifest.xml` 的 `<queries>` 只声明了这一个包。⇒ 渠道服（用户实测 OPPO
 * `com.bmystu.peng1.nearme.gamecenter`）装了也**扫不到**：Android 11+ 的包可见性限制下
 * `getApplicationInfo` 对未声明的包一律抛 `NameNotFoundException`，用户看到的是
 * **「目标游戏未安装」**（抓包页状态徽标 + 「开始抓包」按钮置灰）。
 *
 * ### 匹配规则（2026-09-18 用户拍板：**前缀 + 可选数字 + 必须带点**）
 *
 * ```
 * ^com\.bmystu\.peng\d*\..+$
 * ```
 *
 * | 包名 | 判定 | 说明 |
 * |---|---|---|
 * | `com.bmystu.peng.gw` | 命中 | 官服 |
 * | `com.bmystu.peng.mi` | 命中 | **小米渠道服**（2026-09-18 用户确认）—— 注意是**零数字**后缀 |
 * | `com.bmystu.peng.bilibili` | 命中 | **B站渠道服**（2026-09-18 用户提供）—— 同属**零数字**后缀 |
 * | `com.bmystu.peng1.nearme.gamecenter` | 命中 | OPPO 渠道服（用户实测）—— 是**带数字**后缀 |
 * | `com.bmystu.peng2.xxx` | 命中 | 未来渠道服（规则预留，无需改码） |
 * | `com.bmystu.penguin` / `com.bmystu.penguin.hd` | 排除 | **关键反例**：同发行商其它 App 的根包名是 `peng` 的「更长单词」，必须挡住 |
 * | `com.bmystu.peng` / `com.bmystu.peng1` | 排除 | 无点（用户明确要求「必须带点」） |
 *
 * ⇒ `\d*` 里的**「零个数字」必须保留**：`.gw`（官服）与 `.mi`（小米）都是点前无数字，
 * 把 `\d*` 收紧成 `\d+` 会当场把官服和小米一起排除掉。
 *
 * 正则由 [RULE_TEXT]（内容由 [ROOT] 算出）**唯一一份**编译而来，**不允许手写第二份** —— 否则常量与规则会漂移。
 * ⚠️ 2026-09-18 起**弃用 `Regex.escape`**：它走 `Pattern.quote`，产出 `\Qcom.bmystu.peng\E...` 的引号块
 * 形式 —— **语义相同但打进日志读不出来**（U11 要求日志能自解释「该放宽哪条规则」）。详见 [RULE_TEXT]。
 *
 * ### 为什么用「启动器意图」枚举，而不是 `QUERY_ALL_PACKAGES`
 * Android 11+ 要「看见」第三方包必须声明 `<queries>`。三条路：
 * 1. **声明 `<intent>`（MAIN/LAUNCHER）** —— 本实现采用：**不加新权限**，借「有图标的应用」
 *    这一自然条件枚举，再按 [matches] 过滤；
 * 2. 加 `QUERY_ALL_PACKAGES` 权限 —— 能枚举全部应用，但权限面最宽（应用信息里会明示
 *    「可查询所有应用」）；
 * 3. 逐个写死 `<package>` —— 每出一个新渠道服都要改 Manifest 重新打包，等于没解决本问题。
 *
 * Manifest 里 **1 与 3 同时声明**：`<package>` 四条已知渠道作兜底（万一某些 ROM 不认
 * `<intent>`），`<intent>` 负责覆盖未知渠道。
 *
 * ### 观测打点（U11 最小必做项，2026-09-18）
 * [detect] 与 [resolve] 各打一条 `TargetPkg` 日志（文案由 [detectLogLine] /
 * [nearMissLogLine] / [resolveLogLine] 三个**纯函数**生成 ⇒ 可纯 JVM 单测）。
 * 补的是用户点名的第一个问题 —— 「**为什么提示未获取到应用**」：此前该判断在代码里
 * **一行日志都没有**，用户只看到一句 Toast，分不清是「一个候选都没枚举到」还是
 * 「枚举到了但都被规则拦下」。
 *
 * ⚠️ **刻意不报「被 `<queries>` 挡掉几个」** —— Android 没有 API 能枚举「不可见的包」，
 * 报出来只能是编造。只给**看得见的**事实：可见启动器应用数 / 命中数 / 同发行商样本。
 *
 * ### 生效顺序与**单选**（用户已选定 > 自动检出**仅 1 个** > 官服兜底）
 * 见 [decide]：[SettingsStore] 里用户**选定**的包最优先；未配置过时用 [detect] 的结果，
 * 但**仅在恰好检出 1 个时**才自动采用 —— 检出多个（多渠道用户）返回 [Decision.Multiple]，
 * 由 UI 请用户选一个（**不替用户决定**）；一个都没检出、官服也没装 ⇒ [Decision.None]（未安装）。
 *
 * ⚠️ **为什么是单选而不是多选**（2026-09-18 晚定）：多选会让两个渠道的流量**同时进 tun**，
 * 而视图识别表（`core/dedup/ViewTracker` 的 `seq % 127`）是**全局单例** ⇒ 两套独立 seq
 * 空间撞进同一张表、响应会互相错配；且记录不按渠道隔离。「当前在玩哪个渠道」才是正确语义。
 */
object TargetPackages {

    /** 发行方根包名 —— 渠道差异只出现在它之后（官服跟 `.gw`，小米跟 `.mi`，OPPO 跟 `1.nearme.gamecenter`）。 */
    const val ROOT = "com.bmystu.peng"

    /**
     * 已确认渠道的**取样清单** —— 只服务两件事：`AndroidManifest.xml` 的 `<package>` 兜底、
     * 单测取样。**运行时的判定一律走 [matches] 规则**，本清单不参与匹配（否则新增渠道又得改码）。
     *
     * 官服（原 `GachaVpnService.TARGET_PACKAGE`，2026-09-18 迁到此处）。
     */
    const val OFFICIAL = "com.bmystu.peng.gw"

    /** OPPO 渠道服 —— 2026-09-18 用户实测确认；**带数字**后缀（`1.nearme.gamecenter`）。 */
    const val OPPO = "com.bmystu.peng1.nearme.gamecenter"

    /** 小米渠道服 —— 2026-09-18 用户确认；**零数字**后缀（`.mi`），与官服 `.gw` 同构。 */
    const val XIAOMI = "com.bmystu.peng.mi"

    /** B站渠道服 —— 2026-09-18 用户提供；**零数字**后缀（`.bilibili`），与官服 `.gw` 同构。 */
    const val BILIBILI = "com.bmystu.peng.bilibili"

    /**
     * 规则文本（见类注释的表）—— **正则与日志共用这一份**。
     *
     * ⚠️ 2026-09-18 起不再用 `Regex.escape`：它走 `Pattern.quote`，产出 `\Qcom.bmystu.peng\E...`
     * 这种带引号块的模式 —— 语义相同，但 U11 要求日志里能直接读到「该放宽哪条规则」。
     * [ROOT] 只含字母与点，把点逐字符转义即可 ⇒ **语义与原先完全等价**
     * （`_probe_out/pkg_rule_check.py` 的 13 条等价模拟逐条不受影响）。
     */
    private val RULE_TEXT = "^" + ROOT.replace(".", "\\.") + "\\d*\\..+$"

    /** 匹配规则 —— 由 [RULE_TEXT] 编译而来，故 `RULE.pattern == RULE_TEXT` **恒成立**。 */
    private val RULE = Regex(RULE_TEXT)

    // —— 观测打点（U11 最小必做项，2026-09-18）——

    /**
     * 打点 tag —— 与服务的 `GachaVpn` 分开，便于 `adb logcat -s TargetPkg` 单独盯
     * 「为什么提示未获取到应用」这一条线。`CaptureLog` 会把它右补齐到 11 列。
     */
    private const val LOG_TAG = "TargetPkg"

    /** 「未命中」时最多列几个同发行商可见包名 —— 够判断渠道包在不在可见集里，又不淹掉日志。 */
    private const val SAMPLE_LIMIT = 10

    /** 发行商根（`com.bmystu`）—— 由 [ROOT] 推出，不手写第二份。 */
    private val PUBLISHER = ROOT.substringBeforeLast('.')

    /**
     * 纯函数：[detect] 的命中行。
     *
     * ⚠️ **刻意不报「被 `<queries>` 挡掉几个」**：Android 没有 API 能枚举「不可见的包」，
     * 那个数报出来只能是编造。这里只给**看得见的**两个事实：可见启动器应用数、命中数。
     */
    internal fun detectLogLine(visible: Int, matched: List<Candidate>): String {
        val hit = if (matched.isEmpty()) {
            "命中 0 个"
        } else {
            matched.joinToString(
                prefix = "命中 ${matched.size} 个 [",
                postfix = "]",
                separator = "、",
            ) { "${it.packageName}(${it.label})" }
        }
        return "规则 ${RULE.pattern} · 可见启动器应用 $visible 个 → $hit"
    }

    /**
     * 纯函数：命中 0 个时的**诊断行** —— 只挑「同发行商且未命中规则」的可见包名。
     * 调用方须在 `matched` 为空时调用（否则同发行商里本来就有命中项）。
     *
     * **为什么不挑「可见包名的前 N 个」**：一台手机上百个应用，按包名序前 10 个基本被
     * `com.android` 系列占满，游戏包名**永远进不了样本**，等于白打点。按 [PUBLISHER]
     * 过滤后样本天然很小，且正好覆盖两种失败形态：
     * - 样本**非空** ⇒ 包**看得见**，是**规则没命中**（要放宽规则或加 `<package>`）；
     * - 样本**为空** ⇒ 连一个同发行商包都看不见 ⇒ **没装**，或**被 `<queries>` 挡住**。
     */
    internal fun nearMissLogLine(visiblePackages: List<String>): String {
        val same = visiblePackages
            .filter { it.startsWith(PUBLISHER) && !matches(it) }
            .distinct()
            .sorted()
        if (same.isEmpty()) {
            return "可见列表里没有未命中的 $PUBLISHER 系列包 ⇒ 目标 App 未安装，或对 <queries> 不可见"
        }
        val shown = same.take(SAMPLE_LIMIT)
        val head = if (same.size > shown.size) {
            "可见的同发行商包名 ${same.size} 个（均未命中规则，只列前 $SAMPLE_LIMIT）："
        } else {
            "可见的同发行商包名 ${same.size} 个（均未命中规则）："
        }
        return head + shown.joinToString("、")
    }

    /**
     * 纯函数：[resolve] 的结论行（**单选语义**，2026-09-18 晚由多选改）。
     *
     * 三种成因必须能分开看，因为处置完全不同：
     * - 用户选过但**包已不在设备上** ⇒ 应当提示重选（否则会静默回落到自动检出）；
     * - 自动检出 **≥2 个**且用户未选 ⇒ 需用户去设置页选一个（**不替他决定**）；
     * - 一个都没检出且官服也没装 ⇒ 「未安装」，抓包页置灰。
     */
    internal fun resolveLogLine(
        picked: String?,
        pickedInstalled: Boolean,
        detected: List<String>,
        officialInstalled: Boolean,
        decision: Decision,
    ): String {
        val pickedPart = when {
            picked == null -> "用户未选定"
            pickedInstalled -> "用户已选定 $picked（在设备上）"
            else -> "用户已选定 $picked（**已不在设备上，忽略**）"
        }
        val detectedPart = "自动检出 ${detected.size} 个" +
            if (detected.isEmpty()) "" else " [${detected.joinToString("、")}]"
        val tail = when (decision) {
            is Decision.Single -> "⇒ 接管 1 个 [${decision.packageName}]"
            Decision.Multiple ->
                "⇒ 检出多个但用户未选定，需到设置页「接管范围」选一个（不替用户决定）"
            Decision.None -> "⇒ 接管 0 个 —— 抓包页报「未安装目标游戏」且「开始抓包」置灰"
        }
        return "$pickedPart / $detectedPart / 官服已装 $officialInstalled $tail"
    }

    /** 一枚被检出的候选，供设置页勾选。 */
    data class Candidate(
        /** 完整包名。 */
        val packageName: String,
        /** 应用显示名（`loadLabel`）；取不到时回落为包名。 */
        val label: String,
    )

    /**
     * 纯函数：该包名是否属于目标游戏族。
     *
     * 抽出来的唯一目的是**纯 JVM 可单测**（本函数不碰 `PackageManager`，不需要 `Context`）。
     */
    fun matches(packageName: String): Boolean =
        // 规则 `^…\d*\..+$` 里的 `.` 是通配 **含空白** ⇒ `com.bmystu.peng.gw `（尾随空格）会被
        // `.+` 吃掉、误判命中。包名来自 PackageManager 时不可能带空白，但 settings.json 可被手改；
        // 白名单场景「带空白仍命中」比「不命中」危险（`TargetPackagesTest.padded values are rejected`）。
        // 故先拒绝任何含空白字符的候选，再走用户钉死的规则文本（RULE_TEXT 本身一字不动）。
        packageName.none { it.isWhitespace() } && RULE.matches(packageName)

    /**
     * 给 UI 用的**渠道简称**（纯函数，可纯 JVM 单测）。
     *
     * 已确认渠道给人话名（「官服」「小米渠道」…）；未知渠道回落到「根包名之后的部分」
     * （如 `2.vivo`）—— 比整串包名短，又比统一叫「渠道服」可辨。
     *
     * 用途：设置页「接管范围」行的尾注、抓包页的只读显示 —— **渠道显示名常一字不差**
     * （都叫「夜幕之下」），所以必须落到渠道标识上而不是应用名。
     */
    fun channelLabel(packageName: String): String = when (packageName) {
        OFFICIAL -> "官服"
        XIAOMI -> "小米渠道"
        BILIBILI -> "B站渠道"
        OPPO -> "OPPO 渠道"
        else -> packageName.removePrefix("$ROOT.").ifBlank { packageName }
    }

    /**
     * 本机已安装且**有启动器图标**的候选（按包名排序、按包名去重）。
     *
     * 依赖 Manifest 的 `<queries><intent>` 声明：未声明时 `queryIntentActivities` 只会返回
     * 「自动可见」的包（本 App 自己 / 与它交互过的包），**检不出任何渠道服**。
     *
     * 每次调用打一条 `TargetPkg` 日志（[detectLogLine]；命中 0 时追加 [nearMissLogLine]）。
     */
    fun detect(context: Context): List<Candidate> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = runCatching { queryLauncherActivities(pm, launcher) }.getOrDefault(emptyList())
        // 可见包名（含未命中规则的）—— 命中 0 时要靠它判断「包看不见」还是「规则没命中」。
        val visible = found.mapNotNull { it.activityInfo?.packageName }.distinct()

        val out = LinkedHashMap<String, Candidate>()
        for (info in found) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!matches(pkg)) continue
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            out[pkg] = Candidate(packageName = pkg, label = label.ifBlank { pkg })
        }
        val result = out.values.sortedBy { it.packageName }
        // 打点（U11）：命中行必打；命中 0 时补一条「同发行商可见包」诊断行。
        CaptureLog.i(LOG_TAG, detectLogLine(visible.size, result))
        if (result.isEmpty()) CaptureLog.w(LOG_TAG, nearMissLogLine(visible))
        return result
    }

    /** 该包是否已安装 —— 受 `<queries>` 可见性约束：未声明的包同样一律返回 false。 */
    @Suppress("DEPRECATION")
    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
    }.isSuccess

    /**
     * 解析结论 —— 调用侧据此决定「能否启动抓包」。
     *
     * **单选语义**（2026-09-18 晚由多选改）：[Ready] 至多携带**一个**包名 ——
     * `addAllowedApplication` 只加这一个，杜绝「两个渠道同时进 tun」导致的视图识别错配
     * （详见 `SettingsStore.targetPackage` 的说明）。
     */
    sealed interface Resolution {
        /** 已定：唯一的接管包名。 */
        data class Ready(val packageName: String) : Resolution

        /**
         * 检出了**多个**渠道且用户**未选定** ⇒ **不替用户决定**，调用侧必须请用户选一个
         * （设置页「接管范围」）。静默挑一个会让用户以为抓的是 A、实际抓的是 B。
         */
        data class NeedsChoice(val candidates: List<Candidate>) : Resolution

        /** 一个可接管的都没装 ⇒ 「目标游戏未安装」，调用侧须拒绝启动抓包。 */
        object NotInstalled : Resolution
    }

    /**
     * [decide] 的纯结果。
     *
     * 把「检出多个、需用户选」与「一个都没有」**分开**（而不是都回 null）—— 两者对用户的
     * 处置完全不同：前者引导去选、后者只能提示未安装。
     */
    internal sealed interface Decision {
        data class Single(val packageName: String) : Decision
        object Multiple : Decision
        object None : Decision
    }

    /**
     * 生效的接管包名（给 `addAllowedApplication` 用）。
     *
     * 返回 [Resolution.NotInstalled] 即「目标游戏未安装」，调用侧必须拒绝启动抓包 —— 注意
     * `addAllowedApplication` 对未安装的包会抛 `NameNotFoundException`，故此处**只回已安装项**。
     *
     * 每次调用打一条 `TargetPkg` 结论日志（[resolveLogLine]）。
     */
    fun resolve(context: Context): Resolution {
        val rows = detect(context)
        val detected = rows.map { it.packageName }
        val picked = SettingsStore.get(context).current.targetPackage
        // 用户选定过的包：既可能在检出结果里，也可能是「无启动器活动」的渠道包 ⇒ 单独再确认一次。
        val pickedInstalled = picked?.takeIf { it in detected || isInstalled(context, it) }
        val officialInstalled = isInstalled(context, OFFICIAL)
        val decision = decide(pickedInstalled, detected, officialInstalled)
        // 打点（U11）：三种成因必须能分开看 —— 选过但已卸载 / 检出多个未选 / 一个都没装。
        CaptureLog.i(
            LOG_TAG,
            resolveLogLine(
                picked = picked,
                pickedInstalled = pickedInstalled != null,
                detected = detected,
                officialInstalled = officialInstalled,
                decision = decision,
            ),
        )
        return when (decision) {
            is Decision.Single -> Resolution.Ready(decision.packageName)
            Decision.Multiple -> Resolution.NeedsChoice(rows)
            Decision.None -> Resolution.NotInstalled
        }
    }

    /**
     * [resolve] 的纯逻辑部分（受「用户已选 > 自动检出（**仅 1 个**）> 官服兜底」支配）。
     *
     * ⚠️ 检出 **≥2 个**且用户未选定时返回 [Decision.Multiple]，而**不是**随便挑一个 ——
     * 让用户自己指明「我在玩哪个渠道」正是这个功能存在的前提。
     *
     * @param picked 用户选定且**仍安装**的包名（null = 没选过，或选的那个已卸载）
     */
    internal fun decide(
        picked: String?,
        detected: List<String>,
        officialInstalled: Boolean,
    ): Decision = when {
        picked != null -> Decision.Single(picked)
        detected.size == 1 -> Decision.Single(detected.first())
        detected.size > 1 -> Decision.Multiple
        officialInstalled -> Decision.Single(OFFICIAL)
        else -> Decision.None
    }

    /** `queryIntentActivities` 的分版本调用（API 33 起走 `ResolveInfoFlags`）。 */
    @Suppress("DEPRECATION")
    private fun queryLauncherActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
}
