package com.jinlin.gacha.assistant.persistence

import android.content.Context
import com.jinlin.gacha.assistant.core.meta.MetaLoader
import java.io.IOException

/**
 * 内置元数据缓存的**出厂播种** —— 把 APK 的 `assets/role_cache.json` 拷到
 * `filesDir/cache/role_cache.json`（对齐 PC 的 `<data>/cache/role_cache.json`）。
 *
 * **为什么内置**：PC 的元数据走「远程拉取 → 写缓存」，首次运行必须联网；
 * 移动端随 APK 带一份出厂缓存，使「缓存 → 解析 → 统计引擎 → UI」这条链路
 * **离线即可跑通**，不阻塞在联网拉取（S3 的 `fetch()`）上。联网拉取上线后，
 * 拉取成功即覆盖同名文件（PC 同款语义），种子自然退役。
 *
 * 播种**只在缓存缺失时**发生（见 [MetaLoader.seedIfMissing]），**绝不覆盖**用户已有数据。
 */
object RoleCacheSeed {

    /** assets 内的缓存文件名（与 PC `remote_data.CACHE_FILENAME` 同名）。 */
    const val ASSET_NAME = "role_cache.json"

    /**
     * 确保缓存存在：缺失则从 assets 播种。返回是否真的写入。
     *
     * 幂等：缓存已存在直接返回 false；assets 缺失或读失败返回 false（不抛）。
     */
    fun ensureSeeded(context: Context): Boolean {
        val target = MetaLoader.cacheFile(context.filesDir)
        if (target.exists()) return false
        val seed = try {
            context.assets.open(ASSET_NAME).use { it.readBytes() }
        } catch (e: IOException) {
            return false
        }
        return MetaLoader.seedIfMissing(target, seed)
    }
}
