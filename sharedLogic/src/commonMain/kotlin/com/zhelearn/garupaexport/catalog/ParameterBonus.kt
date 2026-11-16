package com.zhelearn.garupaexport.catalog

data class ParameterBonus(val performance: Int, val technique: Int, val visual: Int) {
    internal operator fun plus(other: ParameterBonus) = ParameterBonus(
        performance + other.performance, technique + other.technique, visual + other.visual,
    )

    companion object {
        val Zero = ParameterBonus(0, 0, 0)
    }
}
