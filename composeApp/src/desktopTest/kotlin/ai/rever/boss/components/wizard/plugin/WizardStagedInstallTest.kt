package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the wizard does to files on disk, and what it refuses to do.
 *
 * The skip guard fixed in this PR made one more input reachable. `disablePlugin` unregisters panels
 * and sets DISABLED but never unloads, so a user-disabled plugin keeps its id in the loader. A
 * DISABLED entry is only counted as installed while its jar exists, so once its recorded path goes
 * stale the wizard offers it again. Both install paths then moved the download onto the installed
 * artifact before `loadPlugin` could refuse the resident id, and the failure branch deleted the file
 * `installed.json` still named.
 *
 * The rule these pin is that nothing already installed is ever overwritten. An earlier attempt
 * replaced the artifact and tried to roll back on failure, which grew failure paths of its own; each
 * of those could lose the artifact the rollback existed to protect. Refusing has no such tail, and
 * it makes the cleanup unambiguous: everything at the destination afterwards belongs to this call.
 *
 * Real files and real moves throughout, because the bug was never in the decision alone.
 */
class WizardStagedInstallTest {
    private val dir: File = Files.createTempDirectory("wizard-staged-install").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun file(
        name: String,
        bytes: String,
    ) = File(dir, name).apply { writeText(bytes) }

    private fun sidecarOf(jar: File) = File(jar.absolutePath + ".sig")

    private fun loaded(id: String) =
        Result.success(
            DynamicPluginInfo(
                manifest =
                    PluginManifest(
                        pluginId = id,
                        displayName = id,
                        version = "1.0.0",
                        apiVersion = "1.0.0",
                        mainClass = "com.example.Main",
                    ),
                jarPath = File(dir, "$id-1.0.0.jar").absolutePath,
                state = PluginState.LOADED,
                loadedAt = 0L,
                enabled = true,
            ),
        )

    private fun alreadyLoaded(id: String) = IllegalStateException("Plugin already loaded: $id")

    private fun refused(id: String): Result<DynamicPluginInfo> = Result.failure(alreadyLoaded(id))

    // -----------------------------------------------------------------
    // Nothing installed is ever overwritten.
    // -----------------------------------------------------------------

    @Test
    fun `an occupied destination is refused and its bytes are untouched`() {
        runBlocking {
            val installed = file("demo-1.0.0.jar", "the installed bytes")
            val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
            var loaderReached = false

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = installed,
                    pluginId = "demo",
                    isResident = { false },
                ) {
                    loaderReached = true
                    loaded("demo")
                }

            assertTrue(result.isFailure)
            assertEquals("the installed bytes", installed.readText(), "byte for byte, untouched")
            assertFalse(loaderReached, "nothing should be loaded when the destination is occupied")
            assertFalse(staged.exists(), "the download is ours, so it is cleaned up")
        }
    }

    @Test
    fun `a resident plugin is refused before anything moves`() {
        runBlocking {
            val installed = file("demo-1.0.0.jar", "the installed bytes")
            val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
            var loaderReached = false

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = installed,
                    pluginId = "demo",
                    isResident = { true },
                ) {
                    loaderReached = true
                    refused("demo")
                }

            assertTrue(result.isFailure)
            assertEquals("the installed bytes", installed.readText())
            assertFalse(loaderReached, "a refusal after the move could not be rolled back")
            assertFalse(staged.exists())
            assertContains(result.exceptionOrNull()?.message.orEmpty(), "Restart BOSS")
        }
    }

    @Test
    fun `the staged signature is cleaned up with its jar on a refusal`() {
        runBlocking {
            val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
            val stagedSidecar = file("demo-1.0.0.jar.downloading.1.sig", "the new signature")

            stageAndInstall(
                downloadedFile = staged,
                finalFile = File(dir, "demo-1.0.0.jar"),
                pluginId = "demo",
                isResident = { true },
            ) { refused("demo") }

            assertFalse(staged.exists())
            assertFalse(stagedSidecar.exists(), "a sidecar must not outlive the jar it describes")
        }
    }

    // -----------------------------------------------------------------
    // A first install promotes the whole artifact.
    // -----------------------------------------------------------------

    @Test
    fun `the signature travels with the jar it signs`() {
        // RemotePluginRepository writes the sidecar beside the DOWNLOAD, so promotion is the moment
        // it has to move. Moving only the jar left every signed store install loading unsigned.
        runBlocking {
            val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
            file("demo-1.0.0.jar.downloading.1.sig", "the signature")
            val finalFile = File(dir, "demo-1.0.0.jar")

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = finalFile,
                    pluginId = "demo",
                    isResident = { false },
                ) { loaded("demo") }

            assertTrue(result.isSuccess)
            assertEquals("the new bytes", finalFile.readText())
            assertTrue(sidecarOf(finalFile).isFile, "the jar must arrive signed")
            assertEquals("the signature", sidecarOf(finalFile).readText())
            assertFalse(staged.exists())
        }
    }

    @Test
    fun `the loader is given the final path, not the staging one`() {
        runBlocking {
            val staged = file("demo-1.0.0.jar.downloading.1", "bytes")
            val finalFile = File(dir, "demo-1.0.0.jar")
            var seen: String? = null

            stageAndInstall(
                downloadedFile = staged,
                finalFile = finalFile,
                pluginId = "demo",
                isResident = { false },
            ) {
                seen = it
                // The signature has to be in place before the loader looks for it.
                assertTrue(sidecarOf(finalFile).isFile || !sidecarOf(staged).exists())
                loaded("demo")
            }

            assertEquals(finalFile.absolutePath, seen)
        }
    }

    // -----------------------------------------------------------------
    // A failure removes only what this call created.
    // -----------------------------------------------------------------

    @Test
    fun `a failed install removes the jar and its signature`() {
        runBlocking {
            val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
            file("demo-1.0.0.jar.downloading.1.sig", "the signature")
            val finalFile = File(dir, "demo-1.0.0.jar")

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = finalFile,
                    pluginId = "demo",
                    isResident = { false },
                ) { refused("demo") }

            assertTrue(result.isFailure)
            assertFalse(finalFile.exists(), "nothing was there before, so nothing should be now")
            assertFalse(sidecarOf(finalFile).exists(), "and no orphan signature is left behind")
        }
    }

    @Test
    fun `a loader that throws is a failure, not an escape`() {
        runBlocking {
            val staged = file("demo-1.0.0.jar.downloading.1", "bytes")
            val finalFile = File(dir, "demo-1.0.0.jar")

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = finalFile,
                    pluginId = "demo",
                    isResident = { false },
                ) { error("the loader blew up") }

            assertTrue(result.isFailure)
            assertFalse(finalFile.exists(), "a throw must not leave an unloadable jar installed")
        }
    }

    @Test
    fun `the original loader failure is passed through rather than replaced`() {
        runBlocking {
            val result =
                stageAndInstall(
                    downloadedFile = file("demo-1.0.0.jar.downloading.1", "bytes"),
                    finalFile = File(dir, "demo-1.0.0.jar"),
                    pluginId = "demo",
                    isResident = { false },
                ) { Result.failure(IllegalStateException("a very specific loader complaint")) }

            assertEquals("a very specific loader complaint", result.exceptionOrNull()?.message)
        }
    }

    // -----------------------------------------------------------------
    // Staging names.
    // -----------------------------------------------------------------

    @Test
    fun `a staging name is unique and cannot be mistaken for a plugin`() {
        val first = stagingNameFor("demo-1.0.0.jar")
        val second = stagingNameFor("demo-1.0.0.jar")

        assertFalse(first.endsWith(".jar"), "a directory scan must not read it as an installed jar")
        assertTrue(first != second, "two installs of one plugin must not collide on a staging name")
    }

    @Test
    fun `the installed name round-trips through the staging name`() {
        val staged = File(dir, stagingNameFor("demo-1.0.0.jar"))

        assertEquals("demo-1.0.0.jar", finalFileForStaged(staged).name)
        assertEquals(dir, finalFileForStaged(staged).parentFile)
    }

    @Test
    fun `a GitHub URL yields its owner and repo, and a clone URL loses its suffix`() {
        assertEquals(
            "risa-labs-inc" to "BossConsole",
            ownerAndRepo("https://github.com/risa-labs-inc/BossConsole"),
        )
        assertEquals("o" to "r", ownerAndRepo("https://github.com/o/r.git"))
        assertEquals("o" to "r", ownerAndRepo("https://github.com/o/r/"))
        assertEquals(null, ownerAndRepo("https://example.com/o/r"))
    }
}
