package com.loinguyen.bandwidth.detekt.dsl

data class QK (
    val q: Int,
    val k: Double
) {
    companion object {
        val ZERO = QK(0, 0.0)
    }
}