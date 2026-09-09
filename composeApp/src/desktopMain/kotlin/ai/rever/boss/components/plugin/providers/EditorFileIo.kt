package ai.rever.boss.components.plugin.providers

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

private val fileIoLogger = BossLogger.forComponent("EditorFileIo")

actual fun readFileContentSafe(
    filePath: String,
    maxSize: Long,
): FileReadOutcome =
    try {
        val file = File(filePath)
        when {
            !file.exists() || !file.isFile -> {
                FileReadOutcome.FileNotFound
            }

            file.length() > maxSize -> {
                FileReadOutcome.FileTooLarge(file.length(), maxSize)
            }

            else -> {
                try {
                    FileReadOutcome.Success(file.readText())
                } catch (e: OutOfMemoryError) {
                    FileReadOutcome.Error("File too large to load into memory: ${e.message}")
                }
            }
        }
    } catch (e: Exception) {
        FileReadOutcome.Error(e.message ?: "Unknown error reading file")
    }

actual fun writeFileContentSafe(
    filePath: String,
    content: String,
): Boolean = guardedWrite(filePath, content)

/**
 * Contains ordinary I/O exceptions and stack/heap exhaustion inside the write operation.
 * Linkage errors and thread termination still propagate. The injectable write allows testing
 * these failure paths without exhausting the test JVM's resources.
 *
 * This cannot contain failures upstream of this function. The recursive provider dispatch
 * reported in editor-tab issues #18 and #27 was already fixed by host PR #262.
 */
@Suppress("TooGenericExceptionCaught")
internal fun guardedWrite(
    filePath: String,
    content: String,
    write: (File, String) -> Unit = { file, text -> file.writeText(text) },
): Boolean =
    try {
        val file = File(filePath)
        // Create parent directories if they don't exist
        file.parentFile?.mkdirs()
        write(file, content)
        true
    } catch (e: Exception) {
        logWriteFailure(filePath, content, e)
        false
    } catch (e: StackOverflowError) {
        logWriteFailure(filePath, content, e)
        false
    } catch (e: OutOfMemoryError) {
        logWriteFailure(filePath, content, e)
        false
    }

/**
 * The path and the size are the two things a report of this needs and did not have. The content
 * itself is never logged: these writes carry whatever the user is editing.
 */
private fun logWriteFailure(
    filePath: String,
    content: String,
    error: Throwable,
) = fileIoLogger.warn(
    LogCategory.EDITOR,
    "Error writing file",
    mapOf(
        "path" to filePath,
        "chars" to content.length,
        "error" to (error::class.simpleName ?: "unknown"),
    ),
    error,
)
