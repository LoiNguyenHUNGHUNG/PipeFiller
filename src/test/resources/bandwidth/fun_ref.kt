// TEST: simple higher-order function
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: @RequireBandwidth(1.0) is too small
// EXPECT_MESSAGE_CONTAINS: body requires ≥ 5.0

package test

import com.loinguyen.bandwidth.detekt.dsl.RequireBandwidth

@RequireBandwidth(minBandwidth = 4.0)
suspend fun fastOp() {
}

@RequireBandwidth(minBandwidth = 1.0) // error: 5.0 > 1.0
suspend fun hofOk(
    @RequireBandwidth(minBandwidth = 5.0) op: suspend () -> Unit
) {
    op()
}
