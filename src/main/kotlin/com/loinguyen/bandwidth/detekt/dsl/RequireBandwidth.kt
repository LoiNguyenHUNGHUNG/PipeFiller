package com.loinguyen.bandwidth.detekt.dsl

import org.jetbrains.kotlin.descriptors.FunctionDescriptor

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class RequireBandwidth(val minBandwidth: Double)

data class RequireBandwidthData(val minBandwidth: Double)

fun FunctionDescriptor.getRequireBandwidthData(): RequireBandwidthData? {
    val ann = annotations.firstOrNull {
        it.fqName?.shortName()?.asString() == "RequireBandwidth"
    } ?: return null

    val bw = ann.allValueArguments.entries
        .firstOrNull { it.key.asString() == "minBandwidth" }
        ?.value?.value as? Double ?: return null

    return RequireBandwidthData(bw)
}