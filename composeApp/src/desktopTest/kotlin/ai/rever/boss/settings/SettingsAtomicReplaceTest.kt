package ai.rever.boss.settings

import ai.rever.boss.focusmode.FocusModeSettingsManager
import ai.rever.boss.performance.PerformanceSettingsManager
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.run.RunnerSettingsManager
import ai.rever.boss.terminal.TerminalLinkSettingsManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * Each settings manager REPLACES its file on save rather than truncating and rewriting it (#1659).
 *
 * `writeText` truncates on open, so between the open and the last byte the file is empty or partial.
 * A load that reads in that window fails to decode, keeps defaults in memory, and the next save then
 * writes those defaults over the user's real settings; a crash in that window leaves the file
 * truncated for good. #1032's mutex orders writers but cannot close either window, because the
 * reader does not take it and a crash does not respect it.
 *
 * Racing a reader against a writer would make this timing-dependent. File IDENTITY does not: a file
 * rewritten in place keeps its inode across a save, and one replaced by an atomic rename gets a new
 * one. So this asserts the identity changes, which is true exactly when the save cannot expose a
 * truncated file. Windows exposes no identity through `fileKey()`, so the check is skipped there
 * and made on the POSIX legs, where the property is the same.
 *
 * Each save is made twice: once to be sure the file exists, since two of the managers load on a
 * background thread, then once to measure.
 */
class SettingsAtomicReplaceTest {
    private fun identity(file: File): Any? {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        return attributes.fileKey()
    }

    private fun assertReplacedOnSave(
        fileName: String,
        save: suspend () -> Unit,
    ) = runBlocking {
        val file = BossDirectories.resolve(fileName)
        save()
        val before = identity(file)
        Assumptions.assumeTrue(before != null, "this filesystem exposes no file identity; checked on POSIX instead")

        save()

        assertNotEquals(
            before,
            identity(file),
            "$fileName must be replaced by an atomic rename, not truncated and rewritten in place",
        )
    }

    @Test
    fun `performance settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave("performance-settings.json") { PerformanceSettingsManager.saveSettings() }

    @Test
    fun `focus-mode settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave("focus-mode-settings.json") { FocusModeSettingsManager.saveSettings() }

    @Test
    fun `runner settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave("runner-settings.json") { RunnerSettingsManager.saveSettings() }

    @Test
    fun `terminal-link settings are replaced on save, never rewritten in place`() =
        assertReplacedOnSave("terminal-link-settings.json") { TerminalLinkSettingsManager.saveSettings() }
}
