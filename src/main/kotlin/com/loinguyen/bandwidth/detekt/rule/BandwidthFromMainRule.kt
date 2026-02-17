package com.loinguyen.bandwidth.detekt

import com.loinguyen.bandwidth.detekt.dsl.QK
import com.loinguyen.bandwidth.detekt.dsl.getDownloadSpecData
import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.rules.isMainFunction
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.utils.IDEAPluginsCompatibilityAPI
import kotlin.math.max

class BandwidthFromMainRule(config: Config) : Rule(config) {

    override val issue = Issue(
        javaClass.simpleName,
        Severity.Maintainability,
        "Infers (q,k) for program rooted at main()",
        Debt.FIVE_MINS,
    )

    private var didAnalyzeMain: Boolean = false
    private val Kcap: Int = valueOrDefault("K", 64)


    override fun visitNamedFunction(function: KtNamedFunction) {
        if (!didAnalyzeMain && function.isMainFunction()) {
            didAnalyzeMain = true

            val bodyExpression = function.bodyExpression
            if (bodyExpression != null) {
                val qk = inferExpr(bodyExpression)

                // q = concurrency, k = max per-download rate
                val concurrency = qk.q
                val maxRate = qk.k

                val capped = minOf(concurrency, Kcap)
                val b = maxRate * capped.toDouble()

                report(
                    CodeSmell(
                        issue,
                        Entity.from(function),
                        message = "BandwidthSummary q=${qk.q} k=${qk.k} K=$Kcap B=$b"
                    )
                )
            }
        }
        super.visitNamedFunction(function)
    }

    fun inferExpr(expression: KtExpression): QK {
        return when (expression) {
            is KtBlockExpression -> inferBlock(expression)
            is KtCallExpression -> inferCall(expression)
            is KtIfExpression -> inferIf(expression)
            else -> QK.ZERO
        }
    }

    private fun inferBlock(block: KtBlockExpression): QK {
        var acc = QK.ZERO
        for (stmt in block.statements) {
            acc = seqentialCombine(acc, inferExpr(stmt))
        }
        return acc
    }

    private fun inferIf(ifExpr: KtIfExpression): QK {
        val condQK = ifExpr.condition?.let { inferExpr(it) } ?: QK.ZERO
        val thenQK = ifExpr.then?.let { inferExpr(it) } ?: QK.ZERO
        val elseQK = ifExpr.`else`?.let { inferExpr(it) } ?: QK.ZERO

        val branchQ = maxOf(thenQK.q, elseQK.q)
        val branchK = maxOf(thenQK.k, elseQK.k)

        return QK(
            q = maxOf(condQK.q, branchQ),
            k = maxOf(condQK.k, branchK)
        )
    }


    @OptIn(IDEAPluginsCompatibilityAPI::class)
    private fun inferCall(call: KtCallExpression): QK {
        val desc = call.getResolvedCall(bindingContext)?.resultingDescriptor as? FunctionDescriptor
            ?: return QK.ZERO

        // 1) Primitive download
        val downloadSpec = desc.getDownloadSpecData()
        if (downloadSpec != null) {
            val rate = downloadSpec.size / downloadSpec.timeout
            return QK(q = 1, k = rate) // q = concurrency, k = maxRate
        }

        // 2) Otherwise: recursively analyze the function body
        val targetFn = desc.source.getPsi() as? KtNamedFunction
            ?: return QK.ZERO

        val body = targetFn.bodyExpression ?: return QK.ZERO

        return inferExpr(body)
    }


    fun seqentialCombine(a: QK, b: QK): QK =
        QK(q = maxOf(a.q, b.q), k = max(a.k, b.k))
}
