package com.zhelearn.garupaexport.profile

import kotlin.io.encoding.Base64

data class ConvertedProfile(
    val json: String,
    val name: String,
    val cardCount: Int,
    val areaItemCount: Int,
    val potentialCount: Int,
    val warnings: List<String>,
)

/** A throwing, string-based boundary for Swift, avoiding per-byte Objective-C bridge calls. */
class ProfileConverter @Throws(Exception::class) constructor(masterJson: String) {
    private val master = MasterData.fromJson(masterJson)

    @Throws(Exception::class)
    fun convertBase64(protobuf: String): ConvertedProfile {
        val account = GameAccountParser.parse(Base64.decode(protobuf))
        val result = BestdoriExporter.export(account, master)
        return ConvertedProfile(
            result.profile.toJsonString(), result.profile.name, result.profile.cards.size,
            result.profile.items.values.sumOf { levels -> levels.count { it != null } },
            account.potentials.size, result.warnings,
        )
    }
}
