// TEST: coroutineScope + two launches => parallelCombine (q sums), k is max rate
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: BandwidthSummary
// EXPECT_MESSAGE_CONTAINS: q=2
// EXPECT_MESSAGE_CONTAINS: k=5.0
// EXPECT_MESSAGE_CONTAINS: B=10.0

package test

import com.loinguyen.bandwidth.detekt.dsl.DownloadSpec
import com.loinguyen.bandwidth.detekt.dsl.Prio
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@DownloadSpec(size = 10.0, timeout = 2.0, prio = Prio.L) // rate = 5.0
fun dlA() {}

@DownloadSpec(size = 8.0, timeout = 2.0, prio = Prio.L) // rate = 4.0
fun dlB() {}

suspend fun main() = coroutineScope {
    launch { dlA() }
    launch { dlB() }
}
