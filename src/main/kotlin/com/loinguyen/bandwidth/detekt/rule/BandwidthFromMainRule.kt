package com.loinguyen.bandwidth.detekt.rule

import com.loinguyen.bandwidth.detekt.dsl.Prio
import com.loinguyen.bandwidth.detekt.dsl.SchedulerKind
import com.loinguyen.bandwidth.detekt.dsl.getDownloadSpecData
import com.loinguyen.bandwidth.detekt.dsl.getRequireBandwidthData
import com.loinguyen.bandwidth.detekt.dsl.getSchedulerPolicyKindOrDefault
import io.gitlab.arturbosch.detekt.api.*
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.utils.IDEAPluginsCompatibilityAPI
import kotlin.math.max

class BandwidthFromMainRule(config: Config) : Rule(config) {

    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "Checks @RequireBandwidth against inferred bandwidth",
        Debt.FIVE_MINS
    )

    /**
     * Current function-level scheduler policy used while analyzing a function body.
     * Default is UNIFORM.
     */
    private var currentSchedulerKind: SchedulerKind = SchedulerKind.UNIFORM

    override fun visitNamedFunction(function: KtNamedFunction) {
        val body = function.bodyExpression ?: return

        val desc = bindingContext[BindingContext.FUNCTION, function] as? FunctionDescriptor
            ?: return

        val requireData = desc.getRequireBandwidthData() ?: return
        val annotatedB = requireData.minBandwidth

        // Resolve scheduler policy annotation on this function (or use default)
        val previousScheduler = currentSchedulerKind
        currentSchedulerKind = desc.getSchedulerPolicyKindOrDefault()

        try {
            val inferredB = inferExpr(body)
            if (annotatedB < inferredB) {
                report(
                    CodeSmell(
                        issue,
                        Entity.from(function),
                        message = "@RequireBandwidth($annotatedB) is too small; body requires ≥ $inferredB"
                    )
                )
            }
        } finally {
            // restore in case Detekt traverses nested declarations
            currentSchedulerKind = previousScheduler
        }

        super.visitNamedFunction(function)
    }

    // =========================
    // Core Inference
    // =========================

    fun inferExpr(expression: KtExpression): Double =
        when (expression) {
            is KtBlockExpression -> inferBlock(expression)
            is KtIfExpression -> inferIf(expression)
            is KtCallExpression ->
                if (isCoroutineScopeExpr(expression, bindingContext))
                    inferCoroutineScope(expression)
                else
                    inferCall(expression)
            is KtProperty -> inferProperty(expression)
            is KtReturnExpression -> expression.returnedExpression?.let { inferExpr(it) } ?: 0.0
            else -> 0.0
        }

    private fun inferProperty(property: KtProperty): Double {
        val init = property.initializer ?: return 0.0
        return inferExpr(init)
    }

    private fun inferBlock(block: KtBlockExpression): Double {
        var acc = 0.0
        for (stmt in block.statements) {
            acc = max(acc, inferExpr(stmt))
        }
        return acc
    }

    private fun inferIf(ifExpr: KtIfExpression): Double {
        val cond = ifExpr.condition?.let { inferExpr(it) } ?: 0.0
        val thenB = ifExpr.then?.let { inferExpr(it) } ?: 0.0
        val elseB = ifExpr.`else`?.let { inferExpr(it) } ?: 0.0
        return max(cond, max(thenB, elseB))
    }

    @OptIn(IDEAPluginsCompatibilityAPI::class)
    private fun inferCall(call: KtCallExpression): Double {
        val desc = call.getResolvedCall(bindingContext)
            ?.resultingDescriptor as? FunctionDescriptor
            ?: return 0.0

        // Primitive download
        desc.getDownloadSpecData()?.let { spec ->
            return spec.size / spec.timeout
        }

        // Modular: use callee's annotation
        desc.getRequireBandwidthData()?.let { req ->
            return req.minBandwidth
        }

        report(
            CodeSmell(
                issue,
                Entity.from(call),
                message = "Call to ${desc.name} is missing @RequireBandwidth"
            )
        )

        return 0.0
    }

    private data class ParallelChild(val bandwidth: Double, val hasHighPriority: Boolean)

    @OptIn(IDEAPluginsCompatibilityAPI::class)
    private fun inferCoroutineScope(call: KtCallExpression): Double {
        val body = call.lambdaArguments.singleOrNull()
            ?.getLambdaExpression()
            ?.bodyExpression
            ?: return 0.0

        var sequentialPart = 0.0
        val parallelChildren = mutableListOf<ParallelChild>()

        for (stmt in body.statements) {
            val launchCall = extractLaunchCall(stmt) ?: run {
                sequentialPart = max(sequentialPart, inferExpr(stmt))
                continue
            }

            val launchBody = launchCall.lambdaArguments.singleOrNull()
                ?.getLambdaExpression()
                ?.bodyExpression
                ?: continue

            val childBandwidth = inferExpr(launchBody)
            val childHasHigh = containsHighPriorityDownload(launchBody)

            parallelChildren += ParallelChild(
                bandwidth = childBandwidth,
                hasHighPriority = childHasHigh
            )
        }

        val parallelBandwidth = combineParallelByScheduler(parallelChildren, currentSchedulerKind)

        return max(sequentialPart, parallelBandwidth)
    }

    /**
     * Supports:
     *   launch { ... }
     *   val j = launch { ... }
     */
    private fun extractLaunchCall(stmt: KtExpression): KtCallExpression? {
        val direct = stmt as? KtCallExpression
        if (direct != null && isLaunchExpr(direct, bindingContext)) return direct

        val prop = stmt as? KtProperty
        val initCall = prop?.initializer as? KtCallExpression
        if (initCall != null && isLaunchExpr(initCall, bindingContext)) return initCall

        return null
    }

    private fun combineParallelByScheduler(
        children: List<ParallelChild>,
        schedulerKind: SchedulerKind
    ): Double {
        if (children.isEmpty()) return 0.0

        return when (schedulerKind) {
            SchedulerKind.UNIFORM -> {
                // Current prototype semantics: all overlap => sum
                children.sumOf { it.bandwidth }
            }

            SchedulerKind.SEQUENTIAL_BATCH -> {
                // Treat child tasks as non-overlapping
                children.maxOf { it.bandwidth }
            }

            SchedulerKind.PHASED_BY_PRIORITY -> {
                // High and low priority phases do not overlap
                val hi = children.filter { it.hasHighPriority }.sumOf { it.bandwidth }
                val lo = children.filterNot { it.hasHighPriority }.sumOf { it.bandwidth }
                max(hi, lo)
            }
        }
    }

    /**
     * Conservative priority summary for a launch body:
     * returns true iff the subtree contains any high-priority primitive download.
     *
     * This is a lightweight approximation of the paper's priority join.
     */
    @OptIn(IDEAPluginsCompatibilityAPI::class)
    private fun containsHighPriorityDownload(expr: KtExpression): Boolean {
        var found = false

        expr.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                if (found) return

                val desc = expression.getResolvedCall(bindingContext)
                    ?.resultingDescriptor as? FunctionDescriptor
                    ?: return

                val spec = desc.getDownloadSpecData() ?: return
                if (spec.prio == Prio.H) {
                    found = true
                }
            }
        })
        return found
    }
}