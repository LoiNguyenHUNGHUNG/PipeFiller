// TEST: List(n) constructor with literal n contributes exact list-size bound
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: @RequireBandwidth(27.0) is too small
// EXPECT_MESSAGE_CONTAINS: body requires ≥ 30.0

package test

import com.loinguyen.bandwidth.detekt.dsl.DownloadSpecData
import com.loinguyen.bandwidth.detekt.dsl.Prio
import com.loinguyen.bandwidth.detekt.dsl.RequireBandwidth
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@DownloadSpecData(size = 3.0, timeout = 1.0, prio = Prio.L) // rate = 3.0
fun fetchTile(id: Int) {}

@RequireBandwidth(minBandwidth = 27.0) // too small: 10 * 3.0 = 30.0
suspend fun loadTiles() = coroutineScope {
    val tiles = List(10) { it }
    tiles.forEach { id ->
        launch { fetchTile(id) }
    }
}