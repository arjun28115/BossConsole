package ai.rever.boss.plugin.repository

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Update-check version ordering.
 *
 * Every case below is one the previous hand-rolled comparison got wrong. It
 * split on "." and dropped any segment that was not a bare integer, so a
 * dropped segment shifted every later one into the wrong position: "1.0.0+build.7"
 * parsed as [1, 7] and compared 7 against the MINOR of the installed version.
 *
 * The two that a user would actually notice are pinned first.
 */
class PluginRepositoryVersionTest {
    private val manager = PluginRepositoryManager()

    @Test
    fun `build metadata does not make a version newer than itself`() {
        // The loop case. "1.0.0+build.7" read as [1, 7], beating [1, 0, 0], so the
        // store offered an update to the version already installed, on every check,
        // forever. SemVer section 10 says build metadata is ignored for precedence.
        assertFalse(manager.isNewerVersion("1.0.0+build.7", "1.0.0"))
    }

    @Test
    fun `a release candidate is never offered over its own release`() {
        // "2.0.0-rc.1" read as [2, 0]; installed "2.0.0" read as [2, 0, 0]. Equal
        // through both present positions, and the old code then compared 0 with 0
        // and fell through to true on the earlier segment, offering a downgrade.
        assertFalse(manager.isNewerVersion("2.0.0-rc.1", "2.0.0"))
    }

    @Test
    fun `a real upgrade over a pre-release install is still offered`() {
        // The silent-stall case. Installed "1.0-beta.5" read as [1, 5], so 5 sat in
        // the MINOR position and beat the 2 of "1.2.3". The genuine update was never
        // offered at all.
        assertTrue(manager.isNewerVersion("1.2.3", "1.0-beta.5"))
    }

    @Test
    fun `ordinary upgrades are offered`() {
        assertTrue(manager.isNewerVersion("1.2.4", "1.2.3"))
        assertTrue(manager.isNewerVersion("1.10.0", "1.9.0"))
        assertTrue(manager.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun `downgrades and equal versions are not offered`() {
        assertFalse(manager.isNewerVersion("1.2.3", "1.2.4"))
        assertFalse(manager.isNewerVersion("1.2.3", "1.2.3"))
        assertFalse(manager.isNewerVersion("1.9.0", "1.10.0"))
    }

    @Test
    fun `a release is newer than its own pre-release`() {
        assertTrue(manager.isNewerVersion("1.2.3", "1.2.3-rc1"))
        assertFalse(manager.isNewerVersion("1.2.3-rc1", "1.2.3"))
    }

    @Test
    fun `pre-releases order among themselves the way SemVer says`() {
        // alpha < beta < rc, by identifier comparison.
        assertTrue(manager.isNewerVersion("1.2.3-rc1", "1.2.3-beta.1"))
        assertFalse(manager.isNewerVersion("1.2.3-beta.1", "1.2.3-rc1"))
    }

    @Test
    fun `a missing patch or minor counts as zero rather than as absent`() {
        assertFalse(manager.isNewerVersion("1.2", "1.2.0"))
        assertFalse(manager.isNewerVersion("1", "1.0.0"))
        assertTrue(manager.isNewerVersion("1.2.1", "1.2"))
    }

    @Test
    fun `an unreadable candidate version is never offered`() {
        // Nothing can be said about it, so proposing it would be a guess. A
        // "v"-prefixed tag is the common case: it is not a SemVer version.
        assertFalse(manager.isNewerVersion("v1.2.3", "1.2.3"))
        assertFalse(manager.isNewerVersion("", "1.2.3"))
        assertFalse(manager.isNewerVersion("not-a-version", "1.2.3"))
    }

    @Test
    fun `a plugin whose installed version is unreadable is still offered an update`() {
        // Deliberately the opposite of the rule above, and the one behaviour this
        // change had to preserve from the old comparison. Such a plugin is already
        // in a broken state; withholding every future update would strand it there.
        assertTrue(manager.isNewerVersion("1.2.3", ""))
        assertTrue(manager.isNewerVersion("1.2.3", "unknown"))
    }
}
