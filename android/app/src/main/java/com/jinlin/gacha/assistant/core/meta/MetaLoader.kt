package com.jinlin.gacha.assistant.core.meta

import com.jinlin.gacha.assistant.core.dedup.MiniJson
import java.io.File
import java.io.IOException

/**
 * 元数据缓存加载器（**纯 JVM、零 Android 依赖**）—— 对应 PC
 * `loaders/remote_data.py` 的「本地缓存」分支（`_load_cache` / `_load_cache_or_raise`）。
 *
 * 缓存位置对齐 PC：`<data>/cache/role_cache.json` → 移动端 `filesDir/cache/role_cache.json`
 * （见 [cacheFile]）。缓存内容 = 远程响应的**原始 JSON 文本**（不加工），故本类只做
 * 「读文本 → [MiniJson] 解析 → [MetaParser] 构造」。
 *
 * 本类**不含网络与 Android API**：联网拉取（S3 的 `fetch()`）与 assets 出厂播种
 * （[com.jinlin.gacha.assistant.persistence.RoleCacheSeed]）都由外层负责，
 * 因此本类可用纯 junit（`TemporaryFolder`）完整覆盖。
 */
object MetaLoader {

    /** 缓存子目录名（对齐 PC `utils/paths.py` 的 `cache/` 子目录，可整体删除重建）。 */
    const val CACHE_DIR = "cache"

    /** 缓存文件名（对齐 PC `remote_data.CACHE_FILENAME`）。 */
    const val CACHE_FILENAME = "role_cache.json"

    /** 缓存文件句柄：`<filesDir>/cache/role_cache.json`。 */
    fun cacheFile(filesDir: File): File = File(File(filesDir, CACHE_DIR), CACHE_FILENAME)

    /**
     * 有效性判定（对齐 PC `_load_cache`：`isinstance(data, dict)` 且**同时**含
     * `characters` 与 `pools` 两个键）。只查「键在不在」，不查类型 —— 与 PC 一致。
     */
    fun isValidCache(root: Any?): Boolean =
        root is Map<*, *> && root.containsKey("characters") && root.containsKey("pools")

    /**
     * 解析缓存文本 → [Meta]；非法 JSON 或缺键返回 `null`。
     *
     * 对齐 PC `_load_cache` 的「不抛错、返回 None、由调用方决定回退」语义。
     */
    fun parse(text: String): Meta? {
        val root: Any? = try {
            MiniJson.decode(text)
        } catch (e: MiniJson.JsonError) {
            return null
        }
        if (!isValidCache(root)) return null
        @Suppress("UNCHECKED_CAST")
        return MetaParser.parse(root as Map<String, Any?>)
    }

    /**
     * 读缓存文件 → [Meta]；**文件不存在 / 读失败 / 格式错一律返回 `null`（不抛）**。
     *
     * 对齐 PC `_load_cache`：`os.path.isfile` 不通过、或 `json.load` 抛错，均返回 None。
     */
    fun read(cacheFile: File): Meta? {
        if (!cacheFile.isFile) return null
        val text = try {
            cacheFile.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return null
        }
        return parse(text)
    }

    /**
     * 缓存**缺失时**写入内置种子（「内置 assets」出厂缓存方案）。
     *
     * **已存在则不覆盖**（保护后续联网拉取到的更新数据），返回是否真的写入。
     * 写失败返回 false 而不抛（对齐 PC `_write_cache` 的「写失败不影响本次」语义）。
     */
    fun seedIfMissing(cacheFile: File, seed: ByteArray): Boolean {
        if (cacheFile.exists()) return false
        return try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(seed)
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * **无条件覆盖写**缓存 —— 联网拉取（`MetadataClient.fetch`）成功后回写的唯一入口。
     *
     * 与 [seedIfMissing]（缺才写、保护联网数据）相反：这里是「拿到远程最新，覆盖旧缓存」。
     * 存**接收到的原始文本**（`role_cache.json` 落的是接口原样返回，不解析、不重排，
     * 对齐 09 §3.4.2「存原始响应」）。失败返回 `false` 而不抛（对齐 PC `_write_cache` 的
     * 「写失败不影响本次拉取」语义）；`fetch` 里成功也算已回写缓存成功。
     *
     * **temp+rename 原子写**（2026-09-29 起，关闭 11 §7 的非原子记录）：先写同目录
     * `*.tmp` 再 `Files.move(REPLACE_EXISTING)`——Android/Linux 落 rename(2)，读侧
     * 要么看到完整旧文件要么完整新文件，无撕裂（启动自动拉取会在后台覆盖缓存，与
     * `StatsProvider` 主线程 [read] 并发；rename 失败兜底退回直接覆盖写，尽力而为）。
     */
    fun writeRaw(cacheFile: File, text: String): Boolean =
        try {
            // 目录守卫：目标路径是目录时直接判失败（否则 temp+rename 会把空目录顶掉反判成功，
            // 破坏「失败返回 false」契约；旧实现 writeText 写目录必 IOException 返 false）。
            if (cacheFile.isDirectory) {
                false
            } else {
                writeRawInner(cacheFile, text)
            }
        } catch (e: IOException) {
            // rename 失败兜底：退回直接覆盖（旧语义，PC `_write_cache` 即非原子）。
            try {
                cacheFile.writeText(text, Charsets.UTF_8)
                true
            } catch (e2: IOException) {
                false
            }
        }

    /** [writeRaw] 的 temp+rename 主体（成功 = rename 完成，无残留 tmp）。 */
    private fun writeRawInner(cacheFile: File, text: String): Boolean {
        cacheFile.parentFile?.mkdirs()
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            java.nio.file.Files.move(
                tmp.toPath(),
                cacheFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            return true
        } finally {
            // move 成功时 tmp 已不存在，delete 幂等；失败时清残留。
            tmp.delete()
        }
    }
}
