// TEST: reports exactly one finding on main
// EXPECT_FINDINGS: 1
// EXPECT_MESSAGE_CONTAINS: Bandwidth analysis placeholder

package test

fun helper() { }

fun main() {
    helper()
}
