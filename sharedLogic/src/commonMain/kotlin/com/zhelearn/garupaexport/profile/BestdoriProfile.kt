package com.zhelearn.garupaexport.profile

import com.zhelearn.garupaexport.catalog.CardCatalog
import com.zhelearn.garupaexport.catalog.CatalogCard
import com.zhelearn.garupaexport.catalog.ParameterBonus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

data class BestdoriCard(
    val id: Int, val level: Int, val master: Int, val skill: Int,
    val ep: Int, val train: Int, val art: Int,
)

data class BestdoriProfile(
    val name: String,
    val server: Int,
    val cards: List<BestdoriCard>,
    val items: Map<String, List<Int?>>,
    val potentials: List<Int>,
) {
    fun toJsonString(): String {
        val json = buildJsonObject {
            put("name", name)
            put("server", server)
            put("cards", JsonArray(cards.map { card -> buildJsonObject {
                put("id", card.id)
                put("level", card.level)
                put("master", card.master)
                put("skill", card.skill)
                put("ep", card.ep)
                put("train", card.train)
                put("art", card.art)
                put("exclude", false)
            } }))
            put("items", buildJsonObject {
                items.forEach { (name, levels) -> put(name, JsonArray(levels.map { it?.let(::JsonPrimitive) ?: JsonNull })) }
                put("potentials", JsonArray(potentials.map(::JsonPrimitive)))
            })
        }
        return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json)
    }
}

data class ProfileExport(val profile: BestdoriProfile, val warnings: List<String>)

/** A refreshed catalog can resolve missing cards or changed stat bonuses. */
class CatalogMismatchException(message: String) : IllegalArgumentException(message)

object BestdoriExporter {
    private val areaLayout = linkedMapOf(
        "PoppinParty" to listOf(1, 6, 11, 16, 21, 26, 31),
        "Afterglow" to listOf(2, 7, 12, 17, 22, 27, 32),
        "HelloHappyWorld" to listOf(5, 10, 15, 20, 25, 30, 35),
        "PastelPalettes" to listOf(3, 8, 13, 18, 23, 28, 33),
        "Roselia" to listOf(4, 9, 14, 19, 24, 29, 34),
        "RaiseASuilen" to listOf(90, 91, 92, 93, 94, 95, 96),
        "Morfonica" to listOf(83, 84, 85, 86, 87, 88, 89),
        "MyGO" to listOf(97, 98, 99, 100, 101, 102, 103),
        "Everyone" to listOf(73, 74, 75, 76, 77, 78, 79),
        "Magazine" to listOf(80, 81, 82),
        "Plaza" to listOf(70, 66, 67, 69),
        "Menu" to listOf(56, 57, 58, 60),
    )

    fun export(account: GameAccount, catalog: CardCatalog): ProfileExport {
        val missing = account.cards.map { it.id }.filter { it !in catalog.cards }
        if (missing.isNotEmpty()) throw CatalogMismatchException(
            "Bestdori 数据缺少卡牌 ${missing.take(10).joinToString()}，请刷新卡牌数据后重新解析",
        )
        require(account.cards.map { it.id }.distinct().size == account.cards.size) { "账户包含重复卡牌" }
        val cards = account.cards.sortedBy { it.id }.map { card ->
            val info = catalog.cards.getValue(card.id)
            val maxLevel = info.levelLimit + if (card.trained) info.trainingLevelBonus else 0
            require(card.level in 1..maxLevel && card.skillLevel in 1..5 && card.masterRank in 0..4) {
                "卡牌 ${card.id} 的等级、技能等级或星光练习等级超出支持范围"
            }
            require(!card.trainedArt || card.trained) { "卡牌 ${card.id} 未特训却使用了特训后立绘" }
            BestdoriCard(
                card.id, card.level, card.masterRank, card.skillLevel,
                inferEpisodes(card, info),
                if (card.trained) 1 else 0, if (card.trainedArt) 1 else 0,
            )
        }
        val warnings = mutableListOf<String>()
        val items = areaLayout.mapValues { (_, ids) -> ids.map { id ->
            account.areaItems[id]?.let { level ->
                require(level > 0) { "区域道具 $id 等级无效" }
                level - 1
            }
        } }
        val supportedAreas = areaLayout.values.flatten().toSet()
        val skipped = (account.areaItems.keys - supportedAreas).sorted()
        if (skipped.isNotEmpty()) warnings.add("Bestdori 没有对应槽位，已跳过区域道具：${skipped.joinToString()}")
        val potentials = (1..40).map { characterId ->
            val levels = account.potentials[characterId]?.values() ?: return@map 1
            require(levels.all { it in 1..200 }) { "角色 $characterId 的潜能等级超出支持范围" }
            if (levels.distinct().size == 1) levels.first() else {
                val average = (levels.sum() / 3.0).roundToInt()
                warnings.add("角色 $characterId 的三项潜能为 ${levels.joinToString("/")}，Bestdori 单值取平均 $average")
                average
            }
        }
        val skippedCharacters = account.potentials.keys.filter { it !in 1..40 }.sorted()
        if (skippedCharacters.isNotEmpty()) warnings.add("Bestdori 不支持这些角色的潜能，已跳过：${skippedCharacters.joinToString()}")
        return ProfileExport(BestdoriProfile(account.nickname.ifBlank { "Profile" }, 3, cards, items, potentials), warnings)
    }

    private fun inferEpisodes(card: GameCard, info: CatalogCard): Int {
        val rankBonus = card.masterRank * 50 * info.rarity
        var bonus = ParameterBonus(rankBonus, rankBonus, rankBonus)
        if (card.trained) bonus += info.trainingBonus
        // Bestdori sums the first `ep` entries. Choose the smallest equivalent
        // value when an animation episode contributes zero to all three stats.
        for (ep in 0..info.episodeBonuses.size) {
            if (bonus == card.appendBonus) return ep
            if (ep < info.episodeBonuses.size) bonus += info.episodeBonuses[ep]
        }
        throw CatalogMismatchException("卡牌 ${card.id} 的属性加成与 Bestdori 数据不匹配，请刷新卡牌数据后重新解析")
    }
}
