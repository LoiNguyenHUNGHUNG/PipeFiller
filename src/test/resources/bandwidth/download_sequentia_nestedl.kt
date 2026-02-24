// TEST: sequential calls keep bandwidth at max(step), not sum
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: @RequireBandwidth(4.0) is too small
// EXPECT_MESSAGE_CONTAINS: body requires ≥ 5.0

package test

import com.loinguyen.bandwidth.detekt.dsl.DownloadSpec
import com.loinguyen.bandwidth.detekt.dsl.Prio
import com.loinguyen.bandwidth.detekt.dsl.RequireBandwidth

@DownloadSpec(size = 10.0, timeout = 2.0, prio = Prio.L) // rate = 5.0
fun dlA() {}

@RequireBandwidth(minBandwidth = 4.0)
fun dlB() {
    dlA()
}
