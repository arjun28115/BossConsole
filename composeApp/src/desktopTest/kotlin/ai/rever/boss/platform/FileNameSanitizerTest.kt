package ai.rever.boss.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FileNameSanitizer.sanitize] takes a name chosen by whatever served the download, so
 * every assertion here is about hostile or degenerate input rather than ordinary names.
 *
 * The two cases at the top are the ones that were wrong: a long extension threw out of
 * the download handler, and a name of only dots came back carrying the trailing dot the
 * sanitizer exists to remove.
 *
 * Stated so nobody assumes more coverage than there is: these assert the returned string
 * only. Whether Windows would accept it is not exercised anywhere, and cannot be from a
 * JVM test on another platform.
 */
class FileNameSanitizerTest {
    @Test
    fun `a name whose extension is longer than the limit does not throw`() {
        // "a." plus 300 characters gives an extension of 301. The limit is 255, so the
        // old budget arithmetic reached take(-46), which rejects a negative count.
        val result = FileNameSanitizer.sanitize("a." + "x".repeat(300))

        assertTrue(result.length <= 255, "result was ${result.length} characters")
        assertTrue(result.isNotBlank())
    }

    @Test
    fun `a name of only dots does not come back ending in a dot`() {
        // The fallback appended the extension computed in step 4, which for these inputs
        // is a lone ".", so it handed back "download." and undid the trailing dot
        // removal. A trailing dot is exactly what is invalid on Windows.
        for (input in listOf(".", "..", "...", " . ")) {
            val result = FileNameSanitizer.sanitize(input)
            assertFalse(result.endsWith("."), "sanitize($input) returned $result")
            assertTrue(result.isNotBlank(), "sanitize($input) returned blank")
        }
    }

    @Test
    fun `no input produces a name ending in a dot or a space`() {
        val inputs =
            listOf(
                "report.pdf",
                "CON",
                "CON.txt",
                "..",
                ".",
                "   ",
                "",
                "a." + "x".repeat(300),
                "x".repeat(300) + ".pdf",
                "file. ",
                "file .",
                "../../etc/passwd",
                "/etc/shadow",
            )
        for (input in inputs) {
            val result = FileNameSanitizer.sanitize(input)
            assertFalse(
                result.endsWith(".") || result.endsWith(" "),
                "sanitize($input) returned '$result'",
            )
        }
    }

    @Test
    fun `every result stays within the length limit`() {
        val inputs = listOf("x".repeat(300), "x".repeat(300) + ".pdf", "a." + "x".repeat(300))
        for (input in inputs) {
            assertTrue(FileNameSanitizer.sanitize(input).length <= 255)
        }
    }

    @Test
    fun `a long name keeps its extension when the extension fits`() {
        val result = FileNameSanitizer.sanitize("x".repeat(300) + ".pdf")

        assertTrue(result.endsWith(".pdf"), "expected the type to survive, got '$result'")
        assertEquals(255, result.length)
    }

    @Test
    fun `ordinary names are returned unchanged`() {
        assertEquals("report.pdf", FileNameSanitizer.sanitize("report.pdf"))
        assertEquals("my file (1).tar.gz", FileNameSanitizer.sanitize("my file (1).tar.gz"))
    }

    @Test
    fun `path traversal is reduced to the file name`() {
        assertEquals("passwd", FileNameSanitizer.sanitize("../../etc/passwd"))
        assertFalse(FileNameSanitizer.sanitize("/etc/shadow").contains("/"))
        assertFalse(FileNameSanitizer.sanitize("..\\..\\windows\\cmd.exe").contains("\\"))
    }

    @Test
    fun `windows device names are defused, whatever their case or extension`() {
        for (name in listOf("CON", "con", "Con", "PRN", "NUL", "COM1", "LPT9")) {
            assertFalse(
                FileNameSanitizer.sanitize(name).uppercase() == name.uppercase(),
                "sanitize($name) left the device name intact",
            )
        }
        assertEquals("_CON.txt", FileNameSanitizer.sanitize("CON.txt"))
    }

    @Test
    fun `a blank name becomes a usable default`() {
        assertEquals("download", FileNameSanitizer.sanitize(""))
        assertEquals("download", FileNameSanitizer.sanitize("   "))
    }

    @Test
    fun `control characters are dropped rather than replaced`() {
        // A bell character inside the name must vanish, not become an underscore, or
        // every name carrying one would gain a stray separator.
        assertEquals("report.pdf", FileNameSanitizer.sanitize("re\u0007port.pdf"))
    }

    @Test
    fun `executable detection is case insensitive and reads the final extension`() {
        assertTrue(FileNameSanitizer.isExecutableFile("setup.EXE"))
        assertTrue(FileNameSanitizer.isExecutableFile("invoice.pdf.exe"))
        assertTrue(FileNameSanitizer.isExecutableFile("script.Sh"))
        assertFalse(FileNameSanitizer.isExecutableFile("report.pdf"))
        assertFalse(FileNameSanitizer.isExecutableFile("noextension"))
    }
}
