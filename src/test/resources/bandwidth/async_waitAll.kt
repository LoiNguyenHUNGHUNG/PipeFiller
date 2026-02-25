// TEST: coroutineScope + async + awaitAll => OK
// EXPECT_FINDINGS: 0

package test

import com.loinguyen.bandwidth.detekt.dsl.DownloadSpecData
import com.loinguyen.bandwidth.detekt.dsl.Prio
import com.loinguyen.bandwidth.detekt.dsl.RequireBandwidth
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@DownloadSpecData(size = 10.0, timeout = 2.0, prio = Prio.L) // rate = 5.0
fun dlA() {}

@DownloadSpecData(size = 8.0, timeout = 2.0, prio = Prio.L) // rate = 4.0
fun dlB() {}

@RequireBandwidth(minBandwidth = 9.0) // exactly 5.0 + 4.0 = 9.0
suspend fun main() = coroutineScope {
    val a = async { dlA() }
    val b = async { dlB() }
    awaitAll(a, b)
}