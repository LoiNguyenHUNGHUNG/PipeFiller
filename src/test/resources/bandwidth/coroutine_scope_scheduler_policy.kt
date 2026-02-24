// TEST: function-level SchedulerPolicy(PHASED_BY_PRIORITY) phases H and L launches
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: @RequireBandwidth(7.0) is too small
// EXPECT_MESSAGE_CONTAINS: body requires ≥ 8.0

package test

import com.loinguyen.bandwidth.detekt.dsl.DownloadSpecData
import com.loinguyen.bandwidth.detekt.dsl.Prio
import com.loinguyen.bandwidth.detekt.dsl.RequireBandwidth
import com.loinguyen.bandwidth.detekt.dsl.SchedulerKind
import com.loinguyen.bandwidth.detekt.dsl.SchedulerPolicy
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@DownloadSpecData(size = 4.0, timeout = 1.0, prio = Prio.H) // rate = 4.0 (H)
fun fetchPreviewA() {}

@DownloadSpecData(size = 4.0, timeout = 1.0, prio = Prio.H) // rate = 4.0 (H)
fun fetchPreviewB() {}

@DownloadSpecData(size = 2.0, timeout = 1.0, prio = Prio.L) // rate = 2.0 (L)
fun prefetchFullA() {}

@DownloadSpecData(size = 2.0, timeout = 1.0, prio = Prio.L) // rate = 2.0 (L)
fun prefetchFullB() {}

@SchedulerPolicy(SchedulerKind.PHASED_BY_PRIORITY)
@RequireBandwidth(minBandwidth = 7.0) // too small: phased => max(H=8.0, L=4.0) = 8.0
suspend fun openPhotoGrid() = coroutineScope {
    launch { fetchPreviewA() }
    launch { fetchPreviewB() }
    launch { prefetchFullA() }
    launch { prefetchFullB() }
}