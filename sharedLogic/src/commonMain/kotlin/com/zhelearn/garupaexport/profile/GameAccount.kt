package com.zhelearn.garupaexport.profile

import com.zhelearn.garupaexport.catalog.ParameterBonus
data class GameCard(
    val id: Int,
    val level: Int,
    val masterRank: Int,
    val skillLevel: Int,
    val trained: Boolean,
    val trainedArt: Boolean,
    val appendBonus: ParameterBonus,
)

data class PotentialLevels(val performance: Int, val technique: Int, val visual: Int) {
    fun values(): List<Int> = listOf(performance, technique, visual)
}

data class GameAccount(
    val nickname: String,
    val cards: List<GameCard>,
    /** categoryId -> actual game level, before Bestdori's zero-based conversion. */
    val areaItems: Map<Int, Int>,
    val potentials: Map<Int, PotentialLevels>,
)

object GameAccountParser {
    fun parse(protobuf: ByteArray): GameAccount {
        require(protobuf.isNotEmpty() && protobuf.size <= 32 * 1024 * 1024) { "存档数据为空或超过大小限制" }
        val root = ProtoReader(protobuf)
        var hasAccount = false
        var nickname = ""
        var cards: Map<Int, GameCard>? = null
        val areaItems = linkedMapOf<Int, Int>()
        var potentials = emptyMap<Int, PotentialLevels>()
        while (true) {
            val tag = root.nextTag() ?: break
            when (tag.number) {
                1 -> { nickname = readNickname(root.message(tag)); hasAccount = true }
                3 -> cards = dictionary(root.message(tag), ::card)
                22 -> dictionary(root.message(tag)) { _, entry -> areaItem(entry) }.values.forEach { (category, level) ->
                    if (category > 0) {
                        require(level > 0) { "区域道具等级无效" }
                        areaItems[category] = level
                    }
                }
                401 -> potentials = dictionary(root.message(tag)) { _, entry -> potential(entry) }
                else -> root.skip(tag)
            }
        }
        require(hasAccount && cards != null) { "响应不是完整的用户存档，缺少账户或卡牌数据" }
        return GameAccount(nickname, cards.values.toList(), areaItems, potentials)
    }

    private fun readNickname(user: ProtoReader): String {
        var nickname = ""
        while (true) {
            val tag = user.nextTag() ?: break
            if (tag.number != 1) { user.skip(tag); continue }
            val account = user.message(tag)
            while (true) {
                val field = account.nextTag() ?: break
                if (field.number == 3) nickname = account.string(field) else account.skip(field)
            }
        }
        return nickname
    }

    private fun <T> dictionary(wrapper: ProtoReader, decode: (Int, ProtoReader) -> T): Map<Int, T> {
        val result = linkedMapOf<Int, T>()
        while (true) {
            val tag = wrapper.nextTag() ?: break
            if (tag.number != 1) { wrapper.skip(tag); continue }
            val entry = wrapper.message(tag)
            var id = 0
            var value: ProtoReader? = null
            while (true) {
                val field = entry.nextTag() ?: break
                when (field.number) {
                    1 -> id = entry.int(field)
                    2 -> value = entry.message(field)
                    else -> entry.skip(field)
                }
            }
            require(id > 0 && value != null) { "存档映射条目不完整" }
            require(id !in result) { "存档映射包含重复 ID：$id" }
            result[id] = decode(id, value)
        }
        return result
    }

    private fun card(id: Int, reader: ProtoReader): GameCard {
        var embeddedId = id
        var level = 0
        var master = 0
        var skill = 0
        var training = "not_doing"
        var art = "normal"
        var append: ParameterBonus? = null
        while (true) {
            val tag = reader.nextTag() ?: break
            when (tag.number) {
                2 -> embeddedId = reader.int(tag)
                3 -> level = reader.int(tag)
                7 -> training = reader.string(tag)
                9 -> art = reader.string(tag)
                11 -> skill = reader.int(tag)
                12 -> append = appendBonus(id, reader.message(tag))
                13 -> master = reader.int(tag)
                else -> reader.skip(tag)
            }
        }
        require(embeddedId == id) { "卡牌 ID 与映射键不一致：$id" }
        require(training in setOf("not_doing", "done")) { "无法识别卡牌 $id 的特训状态：$training" }
        require(art in setOf("normal", "after_training")) { "无法识别卡牌 $id 的立绘状态：$art" }
        require(append != null) { "卡牌 $id 缺少属性加成，无法推算故事状态" }
        return GameCard(id, level, master, skill, training == "done", art == "after_training", append)
    }

    private fun appendBonus(id: Int, reader: ProtoReader): ParameterBonus {
        var performance = 0
        var technique = 0
        var visual = 0
        while (true) {
            val tag = reader.nextTag() ?: break
            when (tag.number) {
                2 -> require(reader.int(tag) == id) { "卡牌 $id 的属性加成 ID 不一致" }
                3 -> performance = reader.int(tag)
                4 -> technique = reader.int(tag)
                5 -> visual = reader.int(tag)
                else -> reader.skip(tag)
            }
        }
        return ParameterBonus(performance, technique, visual)
    }

    private fun areaItem(reader: ProtoReader): Pair<Int, Int> {
        var category = 0
        var level = 0
        while (true) {
            val tag = reader.nextTag() ?: break
            when (tag.number) {
                3 -> category = reader.int(tag)
                4 -> level = reader.int(tag)
                else -> reader.skip(tag)
            }
        }
        return category to level
    }

    private fun potential(reader: ProtoReader): PotentialLevels {
        var performance = 0
        var technique = 0
        var visual = 0
        while (true) {
            val tag = reader.nextTag() ?: break
            when (tag.number) {
                1 -> performance = reader.int(tag)
                2 -> technique = reader.int(tag)
                3 -> visual = reader.int(tag)
                else -> reader.skip(tag)
            }
        }
        return PotentialLevels(performance, technique, visual)
    }
}
