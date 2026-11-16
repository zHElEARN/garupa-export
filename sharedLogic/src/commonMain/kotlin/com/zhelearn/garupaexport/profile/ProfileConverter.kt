package com.zhelearn.garupaexport.profile

import com.zhelearn.garupaexport.catalog.CardCatalog
import kotlin.io.encoding.Base64

data class ConvertedProfile(
    val json: String,
    val name: String,
    val cardCount: Int,
    val areaItemCount: Int,
    val potentialCount: Int,
    val warnings: List<String>,
)

/** A throwing boundary for Swift, avoiding per-byte Objective-C bridge calls. */
class ProfileConverter(private val catalog: CardCatalog) {

    @Throws(Exception::class)
    fun convertBase64(protobuf: String): ConvertedProfile {
        val account = GameAccountParser.parse(Base64.decode(protobuf))
        val result = BestdoriExporter.export(account, catalog)
        return ConvertedProfile(
            result.profile.toJsonString(), result.profile.name, result.profile.cards.size,
            result.profile.items.values.sumOf { levels -> levels.count { it != null } },
            account.potentials.size, result.warnings,
        )
    }
}
