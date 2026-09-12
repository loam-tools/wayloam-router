package tools.loam.wayloam.router.benchmark

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class BenchmarkRunnerTest {
    @Test fun optionsRejectTyposDuplicatesAndMissingDatasetIdentity() {
        assertTrue(runCatching { parseOptions(arrayOf("--root", "/tmp")) }.isFailure)
        assertTrue(runCatching { parseOptions(arrayOf("--root", "/tmp", "--data-version", "v1", "--roote", "x")) }.isFailure)
        assertTrue(runCatching { parseOptions(arrayOf("--root", "a", "--root", "b", "--data-version", "v1")) }.isFailure)
        assertEquals("v1", parseOptions(arrayOf("--root", "a", "--data-version", "v1"))["data-version"])
    }

    @Test fun machineReadableRecordsEscapeErrorMessagesAndKeepNulls() {
        val writer = StringWriter()
        writer.record(linkedMapOf("message" to "a\n\"b\\c", "failed_section" to null, "elapsed_ms" to 42))
        assertEquals("{\"message\":\"a\\u000a\\\"b\\\\c\",\"failed_section\":null,\"elapsed_ms\":42}\n", writer.toString())
    }
}
