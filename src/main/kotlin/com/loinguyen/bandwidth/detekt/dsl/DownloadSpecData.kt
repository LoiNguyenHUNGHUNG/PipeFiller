package com.loinguyen.bandwidth.detekt.dsl

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.resolve.constants.EnumValue

enum class Prio { H, L }

data class DownloadSpecData(
    val size: Double,
    val timeout: Double,
    val prio: Prio = Prio.L
)

fun FunctionDescriptor.getDownloadSpecData(): DownloadSpecData? {
    val ann = this.annotations.firstOrNull {
        it.fqName?.shortName()?.asString() == "DownloadSpecData"
    } ?: return null

    val args = ann.allValueArguments

    val size = args.entries
        .firstOrNull { it.key.asString() == "size" }
        ?.value
        ?.value as? Double

    val timeout = args.entries
        .firstOrNull { it.key.asString() == "timeout" }
        ?.value
        ?.value as? Double

    if (size == null || timeout == null || timeout == 0.0) return null

    val prio = parsePrioArg(
        args.entries.firstOrNull { it.key.asString() == "prio" }?.value
    ) ?: Prio.L
    return DownloadSpecData(size = size, timeout = timeout, prio = prio)
}

private fun parsePrioArg(arg: Any?): Prio? {
    return when (arg) {
        is EnumValue -> {
            when (arg.enumEntryName.asString()) {
                "H" -> Prio.H
                "L" -> Prio.L
                else -> null
            }
        }
        else -> {
            // Fallback: string-based parse (works in some environments)
            val s = arg?.toString() ?: return null
            when {
                s.endsWith(".H") || s == "H" -> Prio.H
                s.endsWith(".L") || s == "L" -> Prio.L
                else -> null
            }
        }
    }
}

