package com.zhelearn.garupaexport.catalog

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** Owns the atomic on-disk cache; callers perform IO off the main thread. */
internal class CatalogStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "bestdori/cards-v1.json"))

    fun load(): CatalogCache? {
        val input = try { file.openRead() } catch (_: FileNotFoundException) { return null }
        // openRead restores a backup after an interrupted write on older Android versions.
        return input.use {
            require(it.channel.size() <= CatalogCache.MAX_JSON_BYTES) { "卡牌数据缓存超过大小限制" }
            CatalogCache(it.readBytes().toString(Charsets.UTF_8))
        }
    }

    fun save(cache: CatalogCache) {
        val output = file.startWrite()
        try {
            output.write(cache.toJsonString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}
