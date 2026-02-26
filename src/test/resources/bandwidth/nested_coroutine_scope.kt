// TEST: coroutineScope + two launches => parallel sum of child bandwidths
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: @RequireBandwidth(9.0) is too small
// EXPECT_MESSAGE_CONTAINS: body requires ≥ 18.0

package test

import com.loinguyen.bandwidth.detekt.dsl.DownloadSpecData
import com.loinguyen.bandwidth.detekt.dsl.Prio
import com.loinguyen.bandwidth.detekt.dsl.RequireBandwidth
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@DownloadSpecData(size = 10.0, timeout = 2.0, prio = Prio.L) // rate = 5.0
fun dlA() {}

@DownloadSpecData(size = 8.0, timeout = 2.0, prio = Prio.L) // rate = 4.0
fun dlB() {}

@RequireBandwidth(minBandwidth = 9.0)
suspend fun main() = coroutineScope {
    val job1 = launch {
        coroutineScope {
            launch { dlA() }
            launch { dlB() }
        }
    }
    val job2 = launch {
        coroutineScope {
            launch { dlB() }
            launch { dlA() }
        }
    }
}