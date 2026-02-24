// TEST: repeat(10) + launch => repeats parallel children
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: @RequireBandwidth(45.0) is too small
// EXPECT_MESSAGE_CONTAINS: body requires ≥ 50.0

package test

import com.loinguyen.bandwidth.detekt.dsl.DownloadSpecData
import com.loinguyen.bandwidth.detekt.dsl.Prio
import com.loinguyen.bandwidth.detekt.dsl.RequireBandwidth
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@DownloadSpecData(size = 5.0, timeout = 1.0, prio = Prio.L) // rate = 5.0
fun fetchThumbnail() {}

@RequireBandwidth(minBandwidth = 45.0) // too small: 10 * 5.0 = 50.0
suspend fun loadGalleryRepeat() = coroutineScope {
    repeat(10) {
        launch { fetchThumbnail() }
    }
}