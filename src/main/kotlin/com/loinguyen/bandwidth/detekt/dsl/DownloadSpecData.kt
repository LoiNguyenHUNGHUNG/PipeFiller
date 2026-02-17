package com.loinguyen.bandwidth.detekt.dsl

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import kotlin.math.max

data class DownloadSpecData(val size: Double, val timeout: Double)

fun FunctionDescriptor.getDownloadSpecData(): DownloadSpecData? {
    val ann = this.annotations.firstOrNull {
        it.fqName?.shortName()?.asString() == "DownloadSpec"
    } ?: return null

    val args = ann.allValueArguments
    val size = args.entries.firstOrNull { it.key.asString() == "size" }?.value?.value as? Double
    val timeout = args.entries.firstOrNull { it.key.asString() == "timeout" }?.value?.value as? Double
    if (size == null || timeout == null || timeout == 0.0) return null
    return DownloadSpecData(size, timeout)
}

