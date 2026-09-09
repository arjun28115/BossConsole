package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.dependency.SemanticVersion
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import java.io.File
import java.nio.file.Files

/** Publishes a verified store download only after its identity and version have been checked. */
internal object StoreRepairArtifact {
    // Runtime/API artifacts have separate bootstrap and filename contracts.
    fun supports(plugin: SystemPluginInfo): Boolean =
        !plugin.downloadOnly && plugin.pluginId !in PluginDependencyResolution.NOT_USER_INSTALLABLE

    fun promote(
        plugin: SystemPluginInfo,
        downloaded: File,
        manifest: PluginManifest,
        storeVersion: String,
        move: (File, File) -> Unit = { source, target -> Files.move(source.toPath(), target.toPath()) },
    ): File {
        require(supports(plugin)) { "Bootstrap artifacts cannot use store repair" }
        require(manifest.pluginId == plugin.pluginId) { "Store JAR declares a different plugin id" }
        require(manifest.version == storeVersion) { "Store JAR version differs from the requested version" }
        val version = requireNotNull(SemanticVersion.parse(manifest.version)) { "Store JAR version is not semver" }
        plugin.minVersion?.let { minimum ->
            val floor = requireNotNull(SemanticVersion.parse(minimum)) { "Invalid system plugin version floor" }
            require(version >= floor) { "Store JAR is older than this host requires" }
        }
        val ipcReason = PluginStoreSetup.ipcIncompatibilityReason(manifest.minIpcVersion)
        require(ipcReason == null) { "Store JAR is IPC-incompatible: $ipcReason" }
        require(downloaded.name.endsWith(".jar.part")) { "Repair download must use a non-scannable part file" }
        // Keep the unique download basename: never overwrite a concurrent install or a loaded JAR.
        val target = File(downloaded.parentFile, downloaded.name.removeSuffix(".part"))
        check(!target.exists()) { "Repair destination already exists" }
        try {
            // Sign first, publish last. Startup cannot scan the new JAR before its sidecar exists.
            PluginSignatureSidecar.persist(target.absolutePath, PluginSignatureSidecar.read(downloaded.absolutePath))
            move(downloaded, target)
        } catch (failure: Exception) {
            target.delete()
            PluginSignatureSidecar.delete(target.absolutePath)
            throw failure
        }
        PluginSignatureSidecar.delete(downloaded.absolutePath)
        return target
    }
}
