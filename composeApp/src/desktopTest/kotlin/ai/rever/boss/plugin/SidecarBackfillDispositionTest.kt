package ai.rever.boss.plugin

import ai.rever.boss.plugin.PluginStoreSetup.BackfillDisposition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the per-entry decision the sidecar backfill drain makes, which is where
 * BossConsole#108's second failure lived.
 *
 * The drain polls its queue, so whatever it does not keep is gone for the life of
 * the process. `scheduleBackgroundUpdateCheck` raises its in-flight flag
 * synchronously, one line before `backfillSidecarIfMissing` enqueues, so "an
 * update check is in flight" described nearly every entry at drain time rather
 * than a rare few. Treating that as a reason to discard meant the backfill
 * usually did nothing at all, silently.
 */
class SidecarBackfillDispositionTest {
    private fun disposition(
        updateInFlight: Boolean = false,
        jarExists: Boolean = true,
        hasSidecar: Boolean = false,
        alreadyAttempted: Boolean = false,
    ) = PluginStoreSetup.backfillDisposition(updateInFlight, jarExists, hasSidecar, alreadyAttempted)

    @Test
    fun `an in-flight update defers the entry rather than dropping it`() {
        // The bug. "Not yet" is not "no", and the queue has already given the
        // entry up by the time this is decided.
        assertEquals(BackfillDisposition.DEFER, disposition(updateInFlight = true))
    }

    @Test
    fun `deferral wins over every terminal reason`() {
        // Ordering, not just the individual answers: a deferred entry has not been
        // attempted and its JAR is mid-replacement, so any rule that reached SKIP
        // first would strand it exactly as before.
        assertEquals(
            BackfillDisposition.DEFER,
            disposition(updateInFlight = true, hasSidecar = true, alreadyAttempted = true),
        )
    }

    @Test
    fun `a deferred entry is still eligible once the update check clears`() {
        // The round trip the re-queue exists to complete.
        assertEquals(BackfillDisposition.DEFER, disposition(updateInFlight = true))
        assertEquals(BackfillDisposition.FETCH, disposition(updateInFlight = false))
    }

    @Test
    fun `a JAR that vanished is dropped`() {
        assertEquals(BackfillDisposition.SKIP, disposition(jarExists = false))
    }

    @Test
    fun `a JAR another path already signed is dropped`() {
        assertEquals(BackfillDisposition.SKIP, disposition(hasSidecar = true))
    }

    @Test
    fun `a plugin that already spent its lookup is dropped`() {
        // What bounds the retries now that the drain is repeatable: getDownloadUrl
        // books a plugin_downloads row, so re-running it per trigger would
        // fabricate downloads for exactly the plugins that cannot be signed.
        assertEquals(BackfillDisposition.SKIP, disposition(alreadyAttempted = true))
    }

    @Test
    fun `an ordinary unsigned JAR is fetched`() {
        assertEquals(BackfillDisposition.FETCH, disposition())
    }
}

/**
 * The stamp that replaced a second full SHA-256 pass over the JAR.
 *
 * It answers one question: are these still the bytes whose digest we sent to the
 * store? Both replacement paths (`Files.move`, `copyTo` onto the same filename)
 * necessarily change size or mtime, so a stat is enough. It is not a tamper check
 * and must not be read as one.
 */
class JarBytesStampTest {
    private fun tempJar(body: String): File =
        File.createTempFile("boss-stamp-", ".jar").apply {
            writeText(body)
            deleteOnExit()
        }

    @Test
    fun `an untouched file still matches its stamp`() {
        val jar = tempJar("original")
        assertTrue(PluginStoreSetup.stillMatchesResolvedBytes(jar, PluginStoreSetup.stampOf(jar)))
        jar.delete()
    }

    @Test
    fun `a file replaced with different bytes no longer matches`() {
        val jar = tempJar("original")
        val stamp = PluginStoreSetup.stampOf(jar)
        jar.writeText("a replacement of a different length")
        assertFalse(PluginStoreSetup.stillMatchesResolvedBytes(jar, stamp))
        jar.delete()
    }

    @Test
    fun `a same-length replacement is caught by mtime`() {
        // The case a length check alone would miss, and the reason mtime is in the
        // stamp: a same-version re-download reuses the filename and can produce a
        // JAR of identical size.
        //
        // The mtime is set explicitly rather than left to the rewrite, because the
        // real replacement lands on the far side of a store round trip. Letting the
        // test race its own filesystem clock would only measure whether two writes
        // fell in the same millisecond.
        val jar = tempJar("aaaa")
        val stamp = PluginStoreSetup.stampOf(jar)
        jar.writeText("bbbb")
        jar.setLastModified(stamp.lastModified + 2_000)
        assertEquals(stamp.length, jar.length(), "the point of this case is that length alone cannot tell")
        assertFalse(PluginStoreSetup.stillMatchesResolvedBytes(jar, stamp))
        jar.delete()
    }

    @Test
    fun `the blind spot is a same-length rewrite within one mtime tick`() {
        // Documenting a limit, not asserting a feature. `File.lastModified` has
        // millisecond resolution, so a replacement that is byte-for-byte the same
        // length AND lands in the same millisecond as the stamp is invisible here,
        // where a second SHA-256 pass would have caught it.
        //
        // That is an accepted trade, not an oversight. The window this guards is
        // opened by a store round trip and closed after it, so a same-millisecond
        // collision is not reachable on the real path — and the stamp was never a
        // tamper check: anyone able to rewrite a JAR in the plugin dir can also
        // backdate it. Load-time store-signature verification is what establishes
        // authenticity.
        //
        // If this ever needs to be airtight, the fix is to re-hash, not to add
        // more stat fields.
        val jar = tempJar("aaaa")
        val stamp = PluginStoreSetup.stampOf(jar)
        jar.writeText("bbbb")
        jar.setLastModified(stamp.lastModified)
        assertTrue(
            PluginStoreSetup.stillMatchesResolvedBytes(jar, stamp),
            "if this now fails the stamp got stronger, which is fine - update this test",
        )
        jar.delete()
    }

    @Test
    fun `a deleted file does not match`() {
        // File.length() returns 0 for a missing file, so without the exists() check
        // a deleted JAR would compare equal to a zero-length stamp.
        val jar = tempJar("original")
        val stamp = PluginStoreSetup.stampOf(jar)
        jar.delete()
        assertFalse(PluginStoreSetup.stillMatchesResolvedBytes(jar, stamp))
    }
}
