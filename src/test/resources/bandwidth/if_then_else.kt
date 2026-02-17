// TEST: sequential downloads take max q, k stays 1
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: B=5.0

package test

import com.loinguyen.bandwidth.detekt.dsl.DownloadSpec
import com.loinguyen.bandwidth.detekt.dsl.Prio

@DownloadSpec(size = 10.0, timeout = 2.0, prio = Prio.L) // q = 5
fun dlA() {}

@DownloadSpec(size = 8.0, timeout = 2.0, prio = Prio.L) // q = 5
fun dlB() {}

fun dlC(x: Int) {
    if(x == 3) {
        dlA()
    }
    else {
        dlB()
    }
}

fun main() {
    dlC(3)
}
