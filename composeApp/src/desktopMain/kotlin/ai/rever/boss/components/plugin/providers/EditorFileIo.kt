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
 * The body of [writeFileContentSafe], with the write injectable so the failure paths can be
 * tested. A test JVM cannot be made to overflow the stack inside `writeText` on demand.
 *
 * **Why this catches two Errors and not just Exception.** The contract of this function is "never
 * throw, report false", and `catch (e: Exception)` did not hold it: `StackOverflowError` is an
 * `Error`, so it went straight through. That is not hypothetical - it is what
 * risa-labs-inc/boss-plugin-editor-tab#18 and #27 report, from two people on unrelated content.
 * The `editor_write_file` tool surfaced a bare `StackOverflowError` instead of its own
 * "Write failed for <path>", and the warn below that would have named the file never ran, which
 * is why #27 says the error "gives no indication of which input caused it".
 *
 * Both are caught deliberately rather than by widening to `Throwable`. A stack overflow unwinds
 * and leaves the JVM usable, and `readFileContentSafe` above already treats `OutOfMemoryError` as
 * a reportable outcome for the same reason. `Throwable` would also swallow `LinkageError` and
 * `ThreadDeath`, which are not this function's to absorb.
 *
 * This does NOT make the write succeed. Whatever recurses is upstream of here - not in this
 * function, which is `mkdirs` plus `writeText`. It converts an opaque crash into a logged failure
 * that names the file and how much was being written, which is where a diagnosis can start.
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
