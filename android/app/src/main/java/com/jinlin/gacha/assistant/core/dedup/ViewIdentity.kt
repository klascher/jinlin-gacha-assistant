package com.jinlin.gacha.assistant.core.dedup

/**
 * 视图身份 —— Kotlin 逐字镜像 PC `gacha_exporter/analysis/pool_view.py` 的
 * `make_view` / `split_view` / `is_all_pools` / `expected_page_offsets`。
 *
 * **视图标识 = `"<pool_id>@<list_type>"`**（协议无 field3 时退化为裸 `"<pool_id>"`）。
 * 同为 pool_id=0 的「全部卡池」与按契约类型聚合的汇总页（活动/遴选）靠 list_type
 * 区分；缺失 list_type 时它们会塌缩成同一个视图标识 `"0"`（H1 撞车，PC 侧打
 * `视图标识回退` warning，mobile 侧由 [ViewTracker] 的 total 分档兜底）。
 *
 * **B8（仅采纳「全部卡池」）** 是本模块最重要的判定：全部卡池是其它所有视图
 * （单池 / 活动契约 / 遴选契约汇总页）的**超集**（实测各视图权威 total 与对应池
 * 求和吻合：792 = 各池总和、421 = 30001~30006、70 = 20005+20007）。只保留它，
 * 去重就退化为「同视图 + 位置可比」的逐槽判定，跨视图歧义（页边界错位、首个观察
 * 起点歧义、翻页方向依赖、坐标系不可比）同时消失。
 *
 * 纯 JVM、零 Android 依赖。
 */
object ViewIdentity {

    /** 视图标识分隔符（镜像 PC `VIEW_SEP`）。 */
    const val VIEW_SEP = "@"

    /** 「全部卡池」的 list_type（镜像 PC `LIST_TYPE_ALL`）。 */
    const val LIST_TYPE_ALL = 0

    /** list_type → 中文标签（UI 展示用，镜像 PC `LIST_TYPE_LABELS`）。 */
    val LIST_TYPE_LABELS: Map<Int, String> = mapOf(
        0 to "全部卡池",
        3 to "活动契约",
        4 to "遴选契约",
    )

    /**
     * 每页记录条数（规则 B4）。日志实测「解析5条」占绝对多数，末页可少
     * （521 条 = 104×5 + 1、792 条 = 158×5 + 2）。仅用于把权威 total 换算成
     * 「应有页的 offset 列表」，以便收尾时报告还缺哪几页。
     */
    const val PAGE_SIZE = 5

    /**
     * 拼装视图标识（镜像 `make_view`）：有 list_type 用 `"pool@type"`，否则仅 `"pool"`。
     *
     * @param poolId 请求业务体 field1（0 = 全部卡池）
     * @param listType 请求业务体 field3；null = 老协议不带 → 退化为裸 pool_id
     */
    fun makeView(poolId: Int, listType: Int?): String =
        if (listType == null) poolId.toString() else "$poolId$VIEW_SEP$listType"

    /**
     * 拆分视图标识为 `(pool_id, list_type?)`（镜像 `split_view`）；无类型段时 list_type=null。
     */
    fun splitView(view: String?): Pair<String, Int?> {
        if (view.isNullOrEmpty()) return "" to null
        val idx = view.indexOf(VIEW_SEP)
        if (idx < 0) return view to null
        return view.substring(0, idx) to view.substring(idx + 1).toIntOrNull()
    }

    /**
     * 该视图是否属于「全部卡池」（`pool_id == "0"` 且 list_type ∈ {全部, 缺省}）。
     *
     * 老协议无 field3 时视图为裸 `"0"`，同样视为全部卡池（镜像 `is_all_pools`）。
     */
    fun isAllPools(view: String?): Boolean {
        if (view == null) return false
        val (poolId, listType) = splitView(view)
        return poolId == "0" && (listType == LIST_TYPE_ALL || listType == null)
    }

    /**
     * 由权威 total 推算该视图「应有页」的 offset 列表（镜像 `expected_page_offsets`）。
     *
     * total=521 → 105 页（末页 offset=520）；total=792 → 159 页（末页 offset=790）。
     * 供收尾缺口报告指出「还缺哪几页」，用户据此回翻补齐。
     *
     * @param total 响应 field2 给出的服务器权威总抽数（<=0 返回空）
     * @param size 每页条数（默认 [PAGE_SIZE]）
     * @return 升序 offset 列表；total<=0 或 size<=0 时为空
     */
    fun expectedPageOffsets(total: Int, size: Int = PAGE_SIZE): List<Int> {
        if (total <= 0 || size <= 0) return emptyList()
        val full = total / size
        val offsets = MutableList(full) { it * size }
        if (total % size != 0) offsets.add(full * size)
        return offsets
    }
}
