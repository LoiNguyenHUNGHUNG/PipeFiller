package com.loinguyen.bandwidth.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.rules.KotlinCoreEnvironmentTest
import io.gitlab.arturbosch.detekt.test.compileAndLintWithContext
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@KotlinCoreEnvironmentTest
internal class BandwidthFromMainRuleTest(private val env: KotlinCoreEnvironment) {

    private val testDir = Path("src/test/resources/bandwidth")

    @Test
    fun `run all bandwidth test files`() {
        val files = testDir.listDirectoryEntries("*.kt").sortedBy { it.fileName.toString() }
        assertTrue(files.isNotEmpty(), "No test files found in $testDir")

        for (file in files) {
            val text = file.readText()
            val spec = TestSpec.parse(text, file.fileName.toString())

            val findings = BandwidthFromMainRule(Config.empty).compileAndLintWithContext(env, text)
            assertEquals(
                spec.expectFindings,
                findings.size,
                "[$file] ${spec.testName}: expected ${spec.expectFindings} findings, got ${findings.size}\n" +
                        findings.joinToString("\n") { "- ${it.message}" }
            )

            val msg = findings.joinToString("\n") { it.message }
            for (needle in spec.expectMessageContains) {
                assertTrue(
                    msg.contains(needle),
                    "[$file] ${spec.testName}: expected message to contain:\n$needle\nbut was:\n$msg"
                )
            }
        }
    }

    private data class TestSpec(
        val testName: String,
        val expectFindings: Int,
        val expectMessageContains: List<String>,
    ) {
        companion object {
            fun parse(text: String, fileName: String): TestSpec {
                fun findAll(prefix: String): List<String> =
                    text.lineSequence()
                        .map { it.trim() }
                        .filter { it.startsWith(prefix) }
                        .map { it.removePrefix(prefix).trim() }
                        .toList()

                fun findOne(prefix: String): String? =
                    text.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.startsWith(prefix) }
                        ?.removePrefix(prefix)
                        ?.trim()

                val testName = findOne("// TEST:") ?: fileName
                val expectFindings = findOne("// EXPECT_FINDINGS:")?.toIntOrNull() ?: 1
                val expectMessageContains = findAll("// EXPECT_MESSAGE_CONTAINS:")

                return TestSpec(
                    testName = testName,
                    expectFindings = expectFindings,
                    expectMessageContains = expectMessageContains
                )
            }
        }
    }
}
