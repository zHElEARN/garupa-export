package com.zhelearn.garupaexport.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class MasterCard(
    val id: Int,
    val characterId: Int,
    val rarity: Int,
    val levelLimit: Int,
    val trainingLevelBonus: Int,
    val episodeIds: List<Int>,
)

class MasterData private constructor(
    val cards: Map<Int, MasterCard>,
) {
    companion object {
        fun fromJson(text: String): MasterData {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1) { "不支持的 master 映射格式" }
            val cards = root.getValue("cards").jsonObject.map { (key, value) ->
                val id = key.toInt()
                val card = value.jsonObject
                val result = MasterCard(
                    id, card.getValue("characterId").jsonPrimitive.int,
                    card.getValue("rarity").jsonPrimitive.int,
                    card.getValue("levelLimit").jsonPrimitive.int,
                    card.getValue("trainingLevelBonus").jsonPrimitive.int,
                    card.getValue("episodeIds").jsonArray.map { it.jsonPrimitive.int },
                )
                require(id > 0 && result.characterId > 0 && result.rarity in 1..5 && result.levelLimit > 0 &&
                    result.trainingLevelBonus >= 0 && result.episodeIds.all { it > 0 } &&
                    result.episodeIds.distinct().size == result.episodeIds.size) { "master 卡牌映射无效：$id" }
                id to result
            }.toMap()
            require(cards.isNotEmpty()) { "master 卡牌映射为空" }
            return MasterData(cards)
        }
    }
}
