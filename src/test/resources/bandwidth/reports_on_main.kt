// TEST: reports exactly one finding on main
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: B=0.0

package test

fun helper() { }

fun main() {
    helper()
}
