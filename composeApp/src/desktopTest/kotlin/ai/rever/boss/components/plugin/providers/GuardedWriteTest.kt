package ai.rever.boss.components.plugin.providers

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Failure containment inside the write operation, independent of upstream MCP failures. */
class GuardedWriteTest {
    private val tmpDir = System.getProperty("java.io.tmpdir")

    private fun tempPath(name: String) = File(tmpDir, "boss-guarded-write-$name").absolutePath

    @Test
    fun `a stack overflow is reported as failure, not thrown`() {
        // Inject the error inside the write boundary; upstream errors cannot be caught here.
        val written =
            guardedWrite(tempPath("overflow"), "<svg>...</svg>") { _, _ ->
                throw StackOverflowError("recursion inside write")
            }

        assertFalse(written, "a StackOverflowError must be reported as a failed write, not escape the call")
    }

    @Test
    fun `an out of memory error is reported as failure too`() {
        // readFileContentSafe in the same file already treats OOM as a reportable outcome; the
        // write path had no equivalent.
        val written = guardedWrite(tempPath("oom"), "x") { _, _ -> throw OutOfMemoryError("heap") }

        assertFalse(written)
    }

    @Test
    fun `an ordinary IO failure still reports false`() {
        // The behaviour that already worked, pinned so widening the catch did not narrow it.
        val written = guardedWrite(tempPath("io"), "x") { _, _ -> throw IOException("read-only filesystem") }

        assertFalse(written)
    }

    @Test
    fun `an error this function has no business absorbing is not swallowed`() {
        // Deliberately NOT `catch (t: Throwable)`. A LinkageError means the JVM is in a state this
        // function cannot report its way out of, and turning it into `false` would hide a broken
        // classpath behind "write failed".
        assertFailsWith<NoClassDefFoundError>("a LinkageError should propagate, not be reported as a failed write") {
            guardedWrite(tempPath("linkage"), "x") { _, _ -> throw NoClassDefFoundError("something/Missing") }
        }
    }

    @Test
    fun `a successful write returns true and writes the content`() {
        val path = tempPath("ok-${System.nanoTime()}")
        try {
            assertTrue(guardedWrite(path, "hello"))
            assertEquals("hello", File(path).readText())
        } finally {
            File(path).delete()
        }
    }

    @Test
    fun `parent directories are created`() {
        val dir = File(tmpDir, "boss-guarded-write-${System.nanoTime()}/nested/deeper")
        val path = File(dir, "f.txt").absolutePath
        try {
            assertTrue(guardedWrite(path, "x"))
            assertTrue(File(path).isFile)
        } finally {
            File(path).delete()
            generateSequence(dir) { it.parentFile }.take(3).forEach { it.delete() }
        }
    }
}
