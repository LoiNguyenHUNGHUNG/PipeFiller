// TEST: sequential calls keep bandwidth at max(step), not sum
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: @RequireBandwidth(4.0) is too small
// EXPECT_MESSAGE_CONTAINS: body requires ≥ 5.0

package test

import com.loinguyen.bandwidth.detekt.dsl.DownloadSpecData
import com.loinguyen.bandwidth.detekt.dsl.Prio
import com.loinguyen.bandwidth.detekt.dsl.RequireBandwidth

@DownloadSpecData(size = 10.0, timeout = 2.0, prio = Prio.L) // q = 5
fun dlA() {}

@DownloadSpecData(size = 3.0, timeout = 1.0, prio = Prio.H) // q = 3
fun dlB() {}

@RequireBandwidth(minBandwidth = 4.0)
fun main() {
    dlA()
    dlB()
}
