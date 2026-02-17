package com.loinguyen.bandwidth.detekt.dsl

enum class Prio { H, L }

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class DownloadSpec(
    val size: Double,
    val timeout: Double,
    val prio: Prio = Prio.L
)