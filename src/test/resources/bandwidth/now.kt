// TEST: NowInAndroid simplified sync flow => OK
// EXPECT_FINDINGS: 0

package test

import com.loinguyen.bandwidth.detekt.dsl.DownloadSpecData
import com.loinguyen.bandwidth.detekt.dsl.Prio
import com.loinguyen.bandwidth.detekt.dsl.RequireBandwidth
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// ---- Primitive downloads (network boundary) ----

@DownloadSpecData(size = 2.0, timeout = 2.0, prio = Prio.L) // rate = 1.0
fun getTopicChangeList() {}

@DownloadSpecData(size = 4.0, timeout = 2.0, prio = Prio.L) // rate = 2.0
fun getTopics() {}

@DownloadSpecData(size = 2.0, timeout = 2.0, prio = Prio.L) // rate = 1.0
fun getNewsResourceChangeList() {}

@DownloadSpecData(size = 6.0, timeout = 2.0, prio = Prio.L) // rate = 3.0
fun getNewsResources() {}


// ---- Repository logic (sequential) ----

@RequireBandwidth(minBandwidth = 2.0) // max(1.0, 2.0) = 2.0
suspend fun syncTopics(): Boolean {
    getTopicChangeList()
    getTopics()
    return true
}

@RequireBandwidth(minBandwidth = 3.0) // max(1.0, 3.0) = 3.0
suspend fun syncNews(): Boolean {
    getNewsResourceChangeList()

    // simplified sequential batching
    repeat(3) {
        getNewsResources()
    }

    return true
}


// ---- Worker-level parallel orchestration ----

@RequireBandwidth(minBandwidth = 5.0) // 2.0 + 3.0 = 5.0
suspend fun syncWorker(): Boolean = coroutineScope {
    val topics = async { syncTopics() }
    val news = async { syncNews() }

    awaitAll(topics, news)
    true
}