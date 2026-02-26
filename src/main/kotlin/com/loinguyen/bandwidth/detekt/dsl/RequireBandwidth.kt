package com.loinguyen.bandwidth.detekt.dsl

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotated
import org.jetbrains.kotlin.types.KotlinType

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
annotation class RequireBandwidth(val minBandwidth: Double)

data class RequireBandwidthData(val minBandwidth: Double)

fun KotlinType.getRequireBandwidthOnType(): RequireBandwidthData? {
    val ann = annotations.firstOrNull {
        it.fqName?.shortName()?.asString() == "RequireBandwidth"
    } ?: return null

    val bw = ann.allValueArguments.entries
        .firstOrNull { it.key.asString() == "minBandwidth" }
        ?.value?.value as? Double ?: return null

    return RequireBandwidthData(bw)
}

// internal helper: shared by functions & parameters
private fun Annotated.readRequireBandwidth(): RequireBandwidthData? {
    val ann = annotations.firstOrNull {
        it.fqName?.shortName()?.asString() == "RequireBandwidth"
    } ?: return null

    val bw = ann.allValueArguments.entries
        .firstOrNull { it.key.asString() == "minBandwidth" }
        ?.value?.value as? Double ?: return null

    return RequireBandwidthData(bw)
}

// For functions
fun FunctionDescriptor.getRequireBandwidthData(): RequireBandwidthData? =
    (this as Annotated).readRequireBandwidth()

// For higher-order parameters
fun ValueParameterDescriptor.getRequireBandwidthRequirement(): RequireBandwidthData? =
    (this as Annotated).readRequireBandwidth()