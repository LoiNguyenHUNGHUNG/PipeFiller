package com.loinguyen.bandwidth.detekt

import com.loinguyen.bandwidth.detekt.dsl.Prio
import com.loinguyen.bandwidth.detekt.dsl.SchedulerKind
import com.loinguyen.bandwidth.detekt.dsl.getDownloadSpecData
import com.loinguyen.bandwidth.detekt.dsl.getRequireBandwidthData
import com.loinguyen.bandwidth.detekt.dsl.getSchedulerPolicyKindOrDefault
import com.loinguyen.bandwidth.detekt.rule.isCoroutineScopeExpr
import com.loinguyen.bandwidth.detekt.rule.isLaunchExpr
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

    private val asyncWithoutAwaitIssue = Issue(
        "${javaClass.simpleName}AsyncWithoutAwaitAll",
        Severity.Defect,
        "async used inside coroutineScope without awaitAll()",
        Debt.FIVE_MINS
    )

    private var currentSchedulerKind: SchedulerKind = SchedulerKind.UNIFORM

    override fun visitNamedFunction(function: KtNamedFunction) {
        val body = function.bodyExpression ?: return

        val desc = bindingContext[BindingContext.FUNCTION, function] as? FunctionDescriptor
            ?: return

        val requireData = desc.getRequireBandwidthData() ?: return
        val annotatedB = requireData.minBandwidth

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
            currentSchedulerKind = previousScheduler
        }

        super.visitNamedFunction(function)
    }

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

        // 0) awaitAll does not itself consume bandwidth; it just waits.
        //    Handle both top-level awaitAll(...) and extension awaitAll on collections.
        if (desc.name.asString() == "awaitAll") {
            return 0.0
        }

        // Primitive download
        desc.getDownloadSpecData()?.let { spec ->
            return spec.size / spec.timeout
        }

        // Modular call
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

    private data class ParallelChild(
        val bandwidth: Double,
        val hasHighPriority: Boolean
    )

    private data class ScopeAcc(
        val sequentialPart: Double,
        val parallelChildren: List<ParallelChild>
    )

    @OptIn(IDEAPluginsCompatibilityAPI::class)
    private fun inferCoroutineScope(call: KtCallExpression): Double {
        val body = call.lambdaArguments.singleOrNull()
            ?.getLambdaExpression()
            ?.bodyExpression
            ?: return 0.0

        val hasAsync = containsAsync(body)
        val hasAwaitAll = containsAwaitAll(body)

        if (hasAsync && !hasAwaitAll) {
            report(
                CodeSmell(
                    asyncWithoutAwaitIssue,
                    Entity.from(body),
                    message = "coroutineScope contains async but no awaitAll()"
                )
            )
        }

        val acc = inferCoroutineScopeBody(body, mutableMapOf())
        val parallelBandwidth = combineParallelByScheduler(acc.parallelChildren, currentSchedulerKind)
        return max(acc.sequentialPart, parallelBandwidth)
    }

    /**
     * Analyze statements inside a coroutineScope-like batch.
     *
     * Supports:
     *  - launch { ... }
     *  - repeat(<int>) { ... }
     *  - list fan-out: xs.forEach { launch { ... } }
     *
     * listSizeEnv tracks known local list cardinalities (exact upper bounds).
     */
    @OptIn(IDEAPluginsCompatibilityAPI::class)
    private fun inferCoroutineScopeBody(
        body: KtBlockExpression,
        listSizeEnv: MutableMap<String, Int>
    ): ScopeAcc {
        var sequentialPart = 0.0
        val parallelChildren = mutableListOf<ParallelChild>()

        for (stmt in body.statements) {
            // 0) Track local list-size bounds from simple property initializers
            if (stmt is KtProperty) {
                val name = stmt.name
                val init = stmt.initializer
                if (name != null && init != null) {
                    inferListUpperBound(init, listSizeEnv)?.let { bound ->
                        listSizeEnv[name] = bound
                    }
                }
            }

            // 1) Single launch (direct or val x = launch {...})
            val launchCall = extractLaunchCall(stmt)
            if (launchCall != null) {
                val launchBody = launchCall.lambdaArguments.singleOrNull()
                    ?.getLambdaExpression()
                    ?.bodyExpression

                if (launchBody != null) {
                    val childBandwidth = inferExpr(launchBody)
                    val childHasHigh = containsHighPriorityDownload(launchBody)
                    parallelChildren += ParallelChild(childBandwidth, childHasHigh)
                }
                continue
            }

            // 1b) Single async (direct or val x = async {...})
            val asyncCall = extractAsyncCall(stmt)
            if (asyncCall != null) {
                val asyncBody = asyncCall.lambdaArguments.singleOrNull()
                    ?.getLambdaExpression()
                    ?.bodyExpression

                if (asyncBody != null) {
                    val childBandwidth = inferExpr(asyncBody)
                    val childHasHigh = containsHighPriorityDownload(asyncBody)
                    parallelChildren += ParallelChild(childBandwidth, childHasHigh)
                }
                continue
            }

            // 2) repeat(n) { ... }
            val repeatInfo = extractRepeatCall(stmt)
            if (repeatInfo != null) {
                val (times, repeatBody) = repeatInfo
                val nested = inferCoroutineScopeBody(repeatBody, listSizeEnv.toMutableMap())

                sequentialPart = max(sequentialPart, nested.sequentialPart)

                repeat(times) {
                    parallelChildren += nested.parallelChildren
                }
                continue
            }

            // 3) xs.forEach { ... } fan-out
            val forEachInfo = extractForEachCall(stmt, listSizeEnv)
            if (forEachInfo != null) {
                val (times, forEachBody) = forEachInfo
                val nested = inferCoroutineScopeBody(forEachBody, listSizeEnv.toMutableMap())

                sequentialPart = max(sequentialPart, nested.sequentialPart)

                repeat(times) {
                    parallelChildren += nested.parallelChildren
                }
                continue
            }

            // 4) Anything else is sequential code in the scope body
            sequentialPart = max(sequentialPart, inferExpr(stmt))
        }

        return ScopeAcc(sequentialPart, parallelChildren)
    }

    private fun extractLaunchCall(stmt: KtExpression): KtCallExpression? {
        val direct = stmt as? KtCallExpression
        if (direct != null && isLaunchExpr(direct, bindingContext)) return direct

        val prop = stmt as? KtProperty
        val initCall = prop?.initializer as? KtCallExpression
        if (initCall != null && isLaunchExpr(initCall, bindingContext)) return initCall

        return null
    }

    @OptIn(IDEAPluginsCompatibilityAPI::class)
    private fun isAsyncCall(call: KtCallExpression): Boolean {
        val desc = call.getResolvedCall(bindingContext)
            ?.resultingDescriptor as? FunctionDescriptor
            ?: return false

        return desc.name.asString() == "async"
    }

    private fun extractAsyncCall(stmt: KtExpression): KtCallExpression? {
        val direct = stmt as? KtCallExpression
        if (direct != null && isAsyncCall(direct)) return direct

        val prop = stmt as? KtProperty
        val initCall = prop?.initializer as? KtCallExpression
        if (initCall != null && isAsyncCall(initCall)) return initCall

        return null
    }

    /**
     * Supports only:
     *   repeat(<int literal>) { ... }
     */
    private fun extractRepeatCall(stmt: KtExpression): Pair<Int, KtBlockExpression>? {
        System.out.println("${stmt.text} ${stmt}")
        val call = when (stmt) {
            is KtCallExpression -> stmt
            is KtProperty -> stmt.initializer as? KtCallExpression
            else -> null
        } ?: return null

        if (call.calleeExpression?.text != "repeat") return null

        val timesExpr = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return null
        val times = parseIntLiteral(timesExpr) ?: return null
        if (times <= 0) return null

        val repeatBody = call.lambdaArguments.singleOrNull()
            ?.getLambdaExpression()
            ?.bodyExpression
            ?: return null

        return times to repeatBody
    }

    private fun extractForEachCall(
        stmt: KtExpression,
        listSizeEnv: Map<String, Int>
    ): Pair<Int, KtBlockExpression>? {
        val dot = when (stmt) {
            is KtDotQualifiedExpression -> stmt
            is KtProperty -> stmt.initializer as? KtDotQualifiedExpression
            else -> null
        } ?: return null

        val call = dot.selectorExpression as? KtCallExpression ?: return null
        if (call.calleeExpression?.text != "forEach") return null

        val receiverExpr = dot.receiverExpression
        val times = inferListUpperBound(receiverExpr, listSizeEnv) ?: return null
        if (times <= 0) return null

        val forEachBody = call.lambdaArguments.singleOrNull()
            ?.getLambdaExpression()
            ?.bodyExpression
            ?: return null

        return times to forEachBody
    }

    /**
     * Quick, local upper-bound inference for list cardinality.
     *
     * Supports:
     *  - listOf(a,b,c) / mutableListOf(...)
     *  - List(10) { ... }
     *  - local variable references from listSizeEnv
     *  - if (...) e1 else e2   ==> max(bound(e1), bound(e2))
     */
    private fun inferListUpperBound(
        expr: KtExpression,
        listSizeEnv: Map<String, Int>
    ): Int? {
        return when (expr) {
            is KtNameReferenceExpression -> {
                listSizeEnv[expr.getReferencedName()]
            }

            is KtIfExpression -> {
                val t = expr.then?.let { inferListUpperBound(it, listSizeEnv) }
                val e = expr.`else`?.let { inferListUpperBound(it, listSizeEnv) }
                if (t != null && e != null) max(t, e) else null
            }

            is KtCallExpression -> {
                val callee = expr.calleeExpression?.text ?: return null
                when (callee) {
                    "listOf", "mutableListOf" -> expr.valueArguments.size
                    "List" -> {
                        val nExpr = expr.valueArguments.firstOrNull()?.getArgumentExpression()
                        parseIntLiteral(nExpr)
                    }
                    else -> null
                }
            }

            is KtBlockExpression -> {
                val lastExpr = expr.statements.lastOrNull() as? KtExpression
                if (lastExpr != null) inferListUpperBound(lastExpr, listSizeEnv) else null
            }

            else -> null
        }
    }

    private fun parseIntLiteral(expr: KtExpression?): Int? {
        return when (expr) {
            is KtConstantExpression -> expr.text.toIntOrNull()
            else -> null
        }
    }

    private fun combineParallelByScheduler(
        children: List<ParallelChild>,
        schedulerKind: SchedulerKind
    ): Double {
        if (children.isEmpty()) return 0.0

        return when (schedulerKind) {
            SchedulerKind.UNIFORM -> children.sumOf { it.bandwidth }
            SchedulerKind.SEQUENTIAL_BATCH -> children.maxOf { it.bandwidth }
            SchedulerKind.PHASED_BY_PRIORITY -> {
                val hi = children.filter { it.hasHighPriority }.sumOf { it.bandwidth }
                val lo = children.filterNot { it.hasHighPriority }.sumOf { it.bandwidth }
                max(hi, lo)
            }
        }
    }

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

    @OptIn(IDEAPluginsCompatibilityAPI::class)
    private fun containsAsync(body: KtBlockExpression): Boolean {
        var found = false

        body.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                if (found) return

                val desc = expression.getResolvedCall(bindingContext)
                    ?.resultingDescriptor as? FunctionDescriptor
                    ?: return

                if (desc.name.asString() == "async") {
                    found = true
                }
            }
        })

        return found
    }

    @OptIn(IDEAPluginsCompatibilityAPI::class)
    private fun containsAwaitAll(body: KtBlockExpression): Boolean {
        var found = false

        body.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                if (found) return

                val desc = expression.getResolvedCall(bindingContext)
                    ?.resultingDescriptor as? FunctionDescriptor
                    ?: return

                if (desc.name.asString() == "awaitAll") {
                    found = true
                }
            }
        })

        return found
    }
}