package com.zhelearn.garupaexport.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CatalogCard(
    val id: Int,
    val rarity: Int,
    val levelLimit: Int,
    val trainingLevelBonus: Int,
    val trainingBonus: ParameterBonus,
    val episodeBonuses: List<ParameterBonus>,
)

/** The public cards/all.5.json response. No game data or episode-ID mapping is required. */
class CardCatalog @Throws(Exception::class) constructor(json: String) {
    val cards: Map<Int, CatalogCard> = parse(json)
    val cardCount: Int get() = cards.size

    private fun parse(text: String): Map<Int, CatalogCard> {
        require(text.length <= MAX_JSON_BYTES) { "Bestdori 卡牌数据超过大小限制" }
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.isNotEmpty()) { "Bestdori 卡牌数据为空" }
        return root.entries.associate { (key, value) ->
            val id = key.toInt()
            val card = value.jsonObject
            val stat = card.getValue("stat").jsonObject
            val training = stat["training"]?.takeUnless { it == JsonNull }?.jsonObject
            val episodes = stat["episodes"]?.takeUnless { it == JsonNull }?.jsonArray.orEmpty()
            val rarity = card.getValue("rarity").jsonPrimitive.int
            val limit = card.getValue("levelLimit").jsonPrimitive.int
            val extra = training?.getValue("levelLimit")?.jsonPrimitive?.int ?: 0
            require(id > 0 && rarity in 1..5 && limit in 1..1000 && extra in 0..1000 && episodes.size <= 2) {
                "Bestdori 卡牌 $id 的数据格式不受支持"
            }
            id to CatalogCard(id, rarity, limit, extra, training?.let(::bonus) ?: ParameterBonus.Zero,
                episodes.map { bonus(it.jsonObject) })
        }.also { require(it.size == root.size) { "Bestdori 卡牌 ID 重复" } }
    }

    private fun bonus(value: JsonObject): ParameterBonus {
        fun field(name: String) = value.getValue(name).jsonPrimitive.int.also {
            require(it in 0..1_000_000) { "Bestdori 卡牌加成无效" }
        }
        return ParameterBonus(field("performance"), field("technique"), field("visual"))
    }

    companion object {
        const val URL = "https://bestdori.com/api/cards/all.5.json"
        const val MAX_JSON_BYTES = 16 * 1024 * 1024
    }
}
