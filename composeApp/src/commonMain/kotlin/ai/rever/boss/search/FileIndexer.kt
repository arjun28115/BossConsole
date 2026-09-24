package ai.rever.boss.search

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

// Note on java.io.File: using it in commonMain is acceptable here because BOSS is a
// desktop-only application (see CLAUDE.md). This avoids unnecessary abstraction for a
// single-platform target.

private val logger = BossLogger.forComponent("FileIndexer")

/**
 * Background file indexer for the global search feature.
 *
 * Scans project directories and maintains a flat list of indexed files
 * for fast searching. Excludes common non-essential directories like
 * build outputs, node_modules, and hidden files.
 *
 * Thread-safe: Uses a mutex to serialize indexing operations. Requests are queued rather than
 * dropped, so callers that share an indexer must avoid submitting unbounded work.
 */
class FileIndexer(
    private val scan: (suspend (String) -> List<IndexedFile>)? = null,
) {
    /** Mutex to ensure only one indexing operation runs at a time. */
    private val indexingMutex = Mutex()

    private val _indexedFiles = MutableStateFlow<List<IndexedFile>>(emptyList())
    val indexedFiles: StateFlow<List<IndexedFile>> = _indexedFiles.asStateFlow()

    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val _indexedPath = MutableStateFlow<String?>(null)
    val indexedPath: StateFlow<String?> = _indexedPath.asStateFlow()

    /**
     * Directories to exclude from indexing.
     */
    private val excludedDirectories =
        setOf(
            ".git",
            ".idea",
            ".gradle",
            ".svn",
            ".hg",
            "build",
            "out",
            "target",
            "node_modules",
            "vendor",
            "__pycache__",
            ".cache",
            "dist",
            "coverage",
            ".next",
            ".nuxt",
            ".venv",
            "venv",
            "env",
            ".env",
        )

    /**
     * File extensions to include in indexing.
     * If empty, all non-hidden files are included.
     */
    private val includedExtensions = emptySet<String>() // Include all files

    /**
     * Maximum directory depth for recursive scanning.
     * Reduced from 20 to 15 to balance coverage vs performance for deeply nested projects.
     * Most source files are within 10 levels; 15 provides buffer for monorepos.
     */
    private val maxDepth = 15

    /**
     * Index all files in the given project path.
     *
     * Thread-safe: Serializes indexing requests with a mutex; queued requests are not dropped.
     *
     * @param projectPath The root directory to index
     * @param forceReindex If true, re-index even if already indexed
     */
    suspend fun indexProject(
        projectPath: String,
        forceReindex: Boolean = false,
    ) {
        indexingMutex.withLock {
            try {
                // Check if already indexed (inside lock to prevent race)
                if (!forceReindex && _indexedPath.value == projectPath && _indexedFiles.value.isNotEmpty()) {
                    logger.debug(LogCategory.FILE, "Project already indexed", mapOf("path" to projectPath))
                    return@withLock
                }

                _isIndexing.value = true

                logger.info(LogCategory.FILE, "Starting file indexing", mapOf("path" to projectPath))
                val startTime = System.currentTimeMillis()

                val files =
                    scan?.invoke(projectPath)
                        ?: withContext(Dispatchers.IO) {
                            scanProjectFiles(projectPath)
                        }

                _indexedFiles.value = files
                _indexedPath.value = projectPath

                val elapsed = System.currentTimeMillis() - startTime
                logger.info(
                    LogCategory.FILE,
                    "File indexing complete",
                    mapOf(
                        "path" to projectPath,
                        "fileCount" to files.size,
                        "elapsedMs" to elapsed,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(LogCategory.FILE, "Error indexing project", error = e)
            } finally {
                _isIndexing.value = false
            }
        }
    }

    /**
     * Clear the current index.
     */
    fun clearIndex() {
        _indexedFiles.value = emptyList()
        _indexedPath.value = null
        logger.debug(LogCategory.FILE, "Index cleared")
    }

    /**
     * Scan all files in the project directory recursively.
     */
    private suspend fun scanProjectFiles(projectPath: String): List<IndexedFile> {
        currentCoroutineContext().ensureActive()
        val root = resolveRoot(projectPath) ?: return emptyList()
        val files = mutableListOf<IndexedFile>()

        scanDirectory(root.absolutePath.toFile(), root, files)

        return files.sortedBy { it.lowerName }
    }

    /**
     * The project root in both spellings, or null when it cannot be indexed.
     *
     * Fails closed when the root itself does not resolve: without a real path there is
     * nothing to confine the walk to, and indexing an unconfined tree is worse than not
     * indexing at all.
     */
    private fun resolveRoot(projectPath: String): IndexRoot? {
        val rootDir = File(projectPath)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            logger.warn(LogCategory.FILE, "Invalid project path", mapOf("path" to projectPath))
            return null
        }
        return runCatching {
            IndexRoot(
                realPath = rootDir.toPath().toRealPath(),
                absolutePath = rootDir.toPath().toAbsolutePath(),
            )
        }.onFailure {
            logger.warn(
                LogCategory.FILE,
                "Cannot resolve project root, not indexing",
                mapOf("path" to projectPath, "error" to it.toString()),
            )
        }.getOrNull()
    }

    /**
     * Recursively scan a directory and add files to the list.
     *
     * Security: every entry is confined to the project root by [isConfined], so a symlink
     * or a Windows directory junction cannot walk the index out of the project.
     */
    private suspend fun scanDirectory(
        dir: File,
        root: IndexRoot,
        files: MutableList<IndexedFile>,
        depth: Int = 0,
    ) {
        currentCoroutineContext().ensureActive()
        // Limit depth to prevent extremely deep traversal
        if (depth > maxDepth) return

        val children = dir.listFiles() ?: return

        for (child in children) {
            currentCoroutineContext().ensureActive()
            if (isSkipped(child, root)) continue

            if (child.isDirectory) {
                // Recurse into subdirectory
                scanDirectory(child, root, files, depth + 1)
            } else if (passesExtensionFilter(child.name)) {
                files.add(
                    IndexedFile(
                        name = child.name,
                        path = child.absolutePath,
                        relativePath = relativeToRoot(child, root),
                    ),
                )
            }
        }
    }

    /**
     * Whether [child] is excluded from the walk outright: hidden, an excluded directory, or
     * outside the project root.
     *
     * Order matters. [isConfined] touches the filesystem, so the two cheap name tests run
     * first and an excluded directory never pays for it.
     */
    private fun isSkipped(
        child: File,
        root: IndexRoot,
    ): Boolean =
        child.name.startsWith(".") ||
            (child.name in excludedDirectories && child.isDirectory) ||
            !isConfined(child, root)

    /**
     * Whether [child] is inside the project root.
     *
     * Only a link can take an entry out of the directory it was listed in, and the walk only
     * ever descends through directories this function has already confined, so a non-link
     * entry is inside the root by construction and needs no resolving. [isLink] is a single
     * attribute read and resolving is a full path walk, so gating one on the other is the same
     * trade `TabPaths.pathsMatch` documents: the expensive check earns its place only where the
     * cheap one cannot answer.
     */
    private fun isConfined(
        child: File,
        root: IndexRoot,
    ): Boolean = !isLink(child) || resolvesInsideRoot(child, root)

    /** Whether [name]'s extension passes the configured filter, which may be empty. */
    private fun passesExtensionFilter(name: String): Boolean =
        includedExtensions.isEmpty() || name.substringAfterLast('.', "").lowercase() in includedExtensions

    /**
     * The path to display for [child], relative to the root AS THE WALK SPELLS IT.
     *
     * This used to slice the child's ABSOLUTE path at the length of the root's CANONICAL
     * path plus one. Those are two different spellings of the same directory, so whenever
     * they differed in length every entry in the index was cut at the wrong offset. On
     * Windows an 8.3 short component in the root (`RUNNER~1` expanding to its long form,
     * which is how `%TEMP%` is commonly handed out) made the offset one character too long
     * and `src\Main.kt` was displayed as `rc\Main.kt`. On macOS a project under `/tmp` (really
     * `/private/tmp`) made the offset longer than the whole path, so every file fell
     * through to its bare name and lost its folder.
     *
     * Relativizing two paths that are both in the walk's own spelling cannot drift. The
     * absolute spelling is deliberately the one used, here and for [IndexedFile.path]: a
     * tab is keyed by the path it was opened with, so handing back the resolved path would
     * make the index disagree with the editor about which file is which.
     */
    private fun relativeToRoot(
        child: File,
        root: IndexRoot,
    ): String =
        try {
            root.absolutePath.relativize(child.toPath().toAbsolutePath()).toString()
        } catch (e: IllegalArgumentException) {
            logger.debug(
                LogCategory.FILE,
                "Falling back to the bare name for a path the root cannot relativize",
                mapOf("path" to child.path, "error" to e.toString()),
            )
            child.name
        }

    /**
     * Get the count of indexed files.
     */
    fun getFileCount(): Int = _indexedFiles.value.size
}

/**
 * Whether [child] is a link of any kind.
 *
 * Both flags are needed. A Windows directory junction is NOT reported as a symbolic link -
 * measured false for a real `mklink /J` - but it does set `isOther`. They come from one
 * attribute read rather than two: [java.nio.file.Files.isSymbolicLink] performs this same
 * read internally and returns a single flag from it, so calling it first would read every
 * entry's attributes twice to answer the common non-link case.
 * Other reparse points, including cloud placeholders, also take the resolve path;
 * the fast path's benefit therefore depends on the filesystem and project contents.
 *
 * A read that fails answers true, so an entry we cannot classify goes to the resolve rather
 * than being waved through.
 */
private fun isLink(child: File): Boolean =
    runCatching {
        Files
            .readAttributes(child.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            .let { it.isSymbolicLink || it.isOther }
    }.getOrDefault(true)

/**
 * Whether [child] resolves to a real path inside [root].
 *
 * Resolution is [java.nio.file.Path.toRealPath], not [java.io.File.canonicalPath].
 * canonicalPath does NOT follow a symlink or a directory junction on Windows (NIO's
 * realPath does, on every platform), so the check this replaces passed for anything
 * reachable through a junction inside the project: the junction resolved to
 * `<root>\link`, trivially under the root, and the whole subtree behind it was indexed.
 * `mklink /J` needs no elevation, so planting one takes only write access in the project.
 *
 * Containment is then decided with [java.nio.file.Path.startsWith], which compares whole
 * name components. The String `startsWith` this replaces also let a sibling whose name
 * merely begins with the root's through, so `C:\w\proj-secrets\creds.env` counted as
 * inside `C:\w\proj`. Both holes close with the one comparison.
 *
 * [ContentSearchService.resolveFile] reaches the same conclusion for the replace path and
 * documents the Windows behaviour; this brings the index walk in line with it.
 */
private fun resolvesInsideRoot(
    child: File,
    root: IndexRoot,
): Boolean {
    val childRealPath =
        runCatching { child.toPath().toRealPath() }
            .onFailure {
                // Skip entries we can't resolve (broken symlinks, permission issues)
                logger.debug(
                    LogCategory.FILE,
                    "Skipping unresolvable path",
                    mapOf("path" to child.path, "error" to it.toString()),
                )
            }.getOrNull() ?: return false

    val inside = childRealPath.startsWith(root.realPath)
    if (!inside) {
        logger.debug(
            LogCategory.FILE,
            "Skipping a link out of the project root",
            mapOf("path" to child.path, "resolved" to childRealPath.toString()),
        )
    }
    return inside
}

/**
 * The project root in the two spellings the walk needs, carried together so the pair cannot
 * be mixed up at a call site.
 *
 * [realPath] decides confinement and is fully resolved. [absolutePath] is the spelling the
 * walk was started with and keys the relative paths shown in the UI. They are different
 * strings whenever the root reaches the filesystem through a link, a junction, or an 8.3
 * short name, which is precisely when conflating them corrupts the index.
 */
private class IndexRoot(
    val realPath: Path,
    val absolutePath: Path,
)
