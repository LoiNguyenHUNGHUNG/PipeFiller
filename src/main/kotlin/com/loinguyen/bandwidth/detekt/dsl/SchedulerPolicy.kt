package com.loinguyen.bandwidth.detekt.dsl

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.name.FqName

/**
 * Built-in scheduler policies for the prototype checker.
 *
 * These are static analysis assumptions about how a coroutineScope batch
 * may overlap its child tasks (launch/async).
 */
enum class SchedulerKind {
    /**
     * Maximally concurrent (default):
     * all child tasks may overlap.
     */
    UNIFORM,

    /**
     * Maximally serialized:
     * child tasks are treated as non-overlapping.
     */
    SEQUENTIAL_BATCH,

    /**
     * Priority-phased:
     * high-priority tasks and low-priority tasks do not overlap.
     * (Each priority class may still overlap internally.)
     */
    PHASED_BY_PRIORITY
}

/**
 * Optional per-function scheduler override.
 *
 * Example:
 *   @SchedulerPolicy(SchedulerKind.PHASED_BY_PRIORITY)
 *   suspend fun loadHome() = coroutineScope { ... }
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SchedulerPolicy(
    val kind: SchedulerKind
)

private val SCHEDULER_POLICY_FQ = FqName("com.loinguyen.bandwidth.detekt.dsl.SchedulerPolicy")

fun FunctionDescriptor.getSchedulerPolicyKindOrDefault(): SchedulerKind {
    val ann = annotations.findAnnotation(SCHEDULER_POLICY_FQ) ?: return SchedulerKind.UNIFORM
    return ann.extractSchedulerKind() ?: SchedulerKind.UNIFORM
}

private fun AnnotationDescriptor.extractSchedulerKind(): SchedulerKind? {
    val value = allValueArguments.entries.firstOrNull()?.value ?: return null

    // value should be enum entry "UNIFORM" / "SEQUENTIAL_BATCH" / "PHASED_BY_PRIORITY"
    val enumName = value.toString().substringAfterLast('.')
    return runCatching { SchedulerKind.valueOf(enumName) }.getOrNull()
}