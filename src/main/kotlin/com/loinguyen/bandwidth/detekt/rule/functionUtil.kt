package com.loinguyen.bandwidth.detekt.rule

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.utils.IDEAPluginsCompatibilityAPI

@OptIn(IDEAPluginsCompatibilityAPI::class)
fun isCoroutineScopeExpr(call: KtCallExpression, bindingContext: BindingContext): Boolean {
    val desc = call.getResolvedCall(bindingContext)?.resultingDescriptor as? FunctionDescriptor
        ?: return false
    val fq = desc.fqNameUnsafe.asString()
    return fq == "kotlinx.coroutines.coroutineScope"
}

@OptIn(IDEAPluginsCompatibilityAPI::class)
fun isLaunchExpr(call: KtCallExpression, bindingContext: BindingContext): Boolean {
    val desc = call.getResolvedCall(bindingContext)?.resultingDescriptor as? FunctionDescriptor
        ?: return false
    if (desc.name.asString() != "launch") return false
    val fq = desc.fqNameUnsafe.asString()
    return fq.contains("kotlinx.coroutines") && fq.endsWith(".launch")
}


