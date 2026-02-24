package com.loinguyen.bandwidth.detekt.dsl

data class QK (
    val q: Int,
    val k: Double
) {
    companion object {
        val ZERO = QK(0, 0.0)
    }
}

data class QKP(val q: Int, val k: Double, val p: Prio) {
    companion object { val ZERO = QKP(0, 0.0, Prio.L) }
}