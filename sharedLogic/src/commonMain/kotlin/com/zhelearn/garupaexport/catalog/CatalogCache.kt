package com.zhelearn.garupaexport.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** A single validated document, atomically persisted by each platform. */
class CatalogCache private constructor(
    private val data: JsonObject,
    val updatedAtMillis: Long,
    val etag: String?,
    val lastModified: String?,
) {
    val catalog = CardCatalog(data.toString())
    val cardCount: Int get() = catalog.cardCount

    @Throws(Exception::class)
    constructor(catalogJson: String, updatedAtMillis: Long, etag: String?, lastModified: String?) :
        this(Json.parseToJsonElement(catalogJson).jsonObject, updatedAtMillis, etag, lastModified)

    @Throws(Exception::class)
    constructor(cacheJson: String) : this(parseCache(cacheJson))

    private constructor(root: JsonObject) : this(
        root.getValue("catalog").jsonObject,
        root.getValue("updatedAtMillis").jsonPrimitive.long,
        root["etag"]?.jsonPrimitive?.contentOrNull,
        root["lastModified"]?.jsonPrimitive?.contentOrNull,
    )

    init { require(updatedAtMillis > 0) { "卡牌数据缓存时间无效" } }

    fun toJsonString(): String = buildJsonObject {
        put("schemaVersion", 1)
        put("updatedAtMillis", updatedAtMillis)
        put("etag", etag?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lastModified", lastModified?.let { JsonPrimitive(it) } ?: JsonNull)
        put("catalog", data)
    }.toString()

    companion object {
        const val MAX_JSON_BYTES = CardCatalog.MAX_JSON_BYTES + 4096

        private fun parseCache(text: String): JsonObject {
            require(text.length <= MAX_JSON_BYTES) { "卡牌数据缓存超过大小限制" }
            return Json.parseToJsonElement(text).jsonObject.also {
                require(it.getValue("schemaVersion").jsonPrimitive.int == 1) { "卡牌数据缓存格式不受支持" }
            }
        }
    }
}
