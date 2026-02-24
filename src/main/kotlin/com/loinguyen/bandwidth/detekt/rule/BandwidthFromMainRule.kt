package com.loinguyen.bandwidth.detekt

import com.loinguyen.bandwidth.detekt.dsl.getDownloadSpecData
import com.loinguyen.bandwidth.detekt.dsl.getRequireBandwidthData
import com.loinguyen.bandwidth.detekt.rule.isCoroutineScopeExpr
import com.loinguyen.bandwidth.detekt.rule.isLaunchExpr
import io.gitlab.arturbosch.detekt.api.*
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.utils.IDEAPluginsCompatibilityAPI
import kotlin.math.max

/**
 * Modular scalar bandwidth checker.
 *
 * Each function must declare:
 *
 *     @RequireBandwidth(minBandwidth = X)
 *
 * The rule:
 *   1. Infers minimum required bandwidth from the function body.
 *   2. Ensures annotation >= inferred value.
 *
 * Semantics:
 *   - Sequential:      max
 *   - Conditional:     max
 *   - Parallel launch: sum
 *   - Primitive download: size / timeout
 *   - Function call:   use callee's @RequireBandwidth
 */
class BandwidthFromMainRule(config: Config) : Rule(config) {

    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "Checks @RequireBandwidth against inferred bandwidth",
        Debt.FIVE_MINS
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        val body = function.bodyExpression ?: return

        val desc = bindingContext[BindingContext.FUNCTION, function] as? FunctionDescriptor
            ?: return

        val requireData = desc.getRequireBandwidthData() ?: return
        val annotatedB = requireData.minBandwidth

        val inferredB = inferExpr(body)
        System.out.println("${function.name}: $annotatedB has $inferredB")
        if (annotatedB < inferredB) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(function),
                    message = "@RequireBandwidth($annotatedB) is too small; body requires ≥ $inferredB"
                )
            )
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
            else -> 0.0
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

        // Missing annotation
        report(
            CodeSmell(
                issue,
                Entity.from(call),
                message = "Call to ${desc.name} is missing @RequireBandwidth"
            )
        )

        return 0.0
    }

    @OptIn(IDEAPluginsCompatibilityAPI::class)
    private fun inferCoroutineScope(call: KtCallExpression): Double {
        val body = call.lambdaArguments.singleOrNull()
            ?.getLambdaExpression()
            ?.bodyExpression
            ?: return 0.0

        var sequentialPart = 0.0
        val parallelParts = mutableListOf<Double>()

        for (stmt in body.statements) {
            val stmtCall = stmt as? KtCallExpression
            if (stmtCall != null && isLaunchExpr(stmtCall, bindingContext)) {
                val launchBody = stmtCall.lambdaArguments.singleOrNull()
                    ?.getLambdaExpression()
                    ?.bodyExpression
                if (launchBody != null) {
                    parallelParts += inferExpr(launchBody)
                }
            } else {
                sequentialPart = max(sequentialPart, inferExpr(stmt))
            }
        }

        val parallelBandwidth = parallelParts.sum()

        return max(sequentialPart, parallelBandwidth)
    }
}