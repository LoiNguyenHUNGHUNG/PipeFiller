// TEST: return higher-order function
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: @RequireBandwidth(5.0) is too small
// EXPECT_MESSAGE_CONTAINS: body requires ≥ 6.0

package test

import com.loinguyen.bandwidth.detekt.dsl.RequireBandwidth

@RequireBandwidth(minBandwidth = 5.0)
suspend fun something() {
}

@RequireBandwidth(minBandwidth = 1.0) // Correct: 1.0 > 0.0 - returning a closure does not account for any bandwidth consumption.
fun makeOp(
    op: @RequireBandwidth(minBandwidth = 5.0) suspend () -> Unit
): @RequireBandwidth(minBandwidth = 6.0) suspend () -> Unit // Correct: 6.0 > 5.0 {
    return {
        op()
    }
}

@RequireBandwidth(minBandwidth = 5.0) // Effect error: body requires ≥ 6.0
fun use() {
    val f = makeOp(::something)
    f()
}