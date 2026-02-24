package com.loinguyen.bandwidth.detekt.dsl

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import kotlin.math.max

interface SchedulerAbstraction {
    fun combine(children: List<QKP>): QKP
}

// ----------------------------------------------------
// Annotation to tie a coroutineScope to an abstraction
// ----------------------------------------------------

@Target(AnnotationTarget.EXPRESSION, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class UseScheduler(val abstraction: kotlin.reflect.KClass<out SchedulerAbstraction>)


/**
 * -----------------------------------------
 * DSL ops (the ONLY ops allowed in combine)
 * -----------------------------------------
 *
 * These operators are intentionally tiny and side-effect free, so a checker can:
 *  - recognize them reliably,
 *  - trust simple algebraic invariants (monotonicity, joins),
 *  - evaluate scheduler abstractions deterministically.
 *
 * Terminology:
 *  - q (Int): maximum number of downloads that may run concurrently in a fragment.
 *  - k (Double): maximum per-download required rate (e.g., size / timeout) in a fragment.
 *  - p (Prio): priority tag (H or L).
 *
 * A QKP is a summary triple (q,k,p) for a child job/scope.
 */
object Ops {

    /**
     * Returns the larger of two integers.
     */
    fun max(a: Int, b: Int): Int = kotlin.math.max(a, b)

    /**
     * Returns the larger of two doubles.
     */
    fun max(a: Double, b: Double): Double = kotlin.math.max(a, b)

    /**
     * Returns the maximum k among all children.
     */
    fun maxK(children: List<QKP>): Double =
        children.fold(0.0) { acc, c -> max(acc, c.k) }

    /**
     * Sums q over children whose priority equals p.
     *
     */
    fun sumQWhereP(children: List<QKP>, p: Prio): Int =
        children.fold(0) { acc, c -> acc + if (c.p == p) c.q else 0 }
}
