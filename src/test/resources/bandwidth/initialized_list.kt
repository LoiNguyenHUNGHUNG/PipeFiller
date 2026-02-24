// TEST: listOf local variable + forEach + launch => fan-out by counted elements
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
fun fetchThumbnail(url: String) {}

@RequireBandwidth(minBandwidth = 45.0) // too small: 10 * 5.0 = 50.0
suspend fun loadGallery() = coroutineScope {
    val images = listOf("a","b","c","d","e","f","g","h","i","j")
    images.forEach { url ->
        launch { fetchThumbnail(url) }
    }
}