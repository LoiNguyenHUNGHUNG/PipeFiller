// TEST: coroutineScope + async without awaitAll => error
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: coroutineScope contains async but no awaitAll()

package test

import com.loinguyen.bandwidth.detekt.dsl.DownloadSpecData
import com.loinguyen.bandwidth.detekt.dsl.Prio
import com.loinguyen.bandwidth.detekt.dsl.RequireBandwidth
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@DownloadSpecData(size = 10.0, timeout = 2.0, prio = Prio.L) // rate = 5.0
fun dlA() {}

@DownloadSpecData(size = 8.0, timeout = 2.0, prio = Prio.L) // rate = 4.0
fun dlB() {}

@RequireBandwidth(minBandwidth = 9.0) // big enough; we only want the async-without-await error
suspend fun main() = coroutineScope {
    val a = async { dlA() }
    val b = async { dlB() }
    // missing awaitAll(a, b)
}