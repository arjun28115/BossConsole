package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.snippets.Snippet
import ai.rever.boss.snippets.SnippetLibraryManager
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Host MCP tool provider exposing the operator's reusable prompt/command snippets
 * ([SnippetLibraryManager]) to AI agents and automation clients.
 *
 * Tools exposed:
 * - snippets_list / snippet_list (read-only)
 * - snippet_get (read-only)
 * - snippet_save (mutating)
 * - snippet_delete (mutating)
 *
 * The read tools declare `readOnly = true`, so the mutating gate's fail-closed OR leaves them at
 * the default ALLOW; the write tools declare `readOnly = false`, so the same gate classifies them
 * as mutating (neither name is in the host's known-mutating list) and they default to ASK, with
 * the approval dialog the operator's confirmation. This mirrors [WorkspaceMcpToolProvider]: no
 * tool declares `requiredPermissions`, since the registry is a loopback-only server for the local
 * machine's own agents and an undeclared tool is permitted there.
 */
@Suppress("TooManyFunctions")
object SnippetMcpToolProvider : McpToolProvider {
    override val providerId: String = "boss-snippets"

    /** A page an agent can read in one go. */
    internal const val DEFAULT_LIST_LIMIT = 50

    /**
     * The library's size. It had none: this tool is the only writer, every save rewrites the whole
     * file, and the list is allowed without asking - so an agent could grow both without limit.
     * Generous for a prompt library; reaching it asks for a delete, not a bigger number.
     */
    internal const val MAX_SNIPPETS = 500

    /** A title a person scans in a picker. */
    internal const val MAX_TITLE_CHARS = 200

    /** A long prompt fits; a document does not. */
    internal const val MAX_BODY_CHARS = 20_000

    /** The raw comma-separated tag string. */
    internal const val MAX_TAGS_CHARS = 500

    override fun tools(): List<McpToolDefinition> =
        listOf(
            createListTool("snippets_list"),
            createListTool("snippet_list"),
            createGetTool("snippet_get"),
            createSaveTool("snippet_save"),
            createDeleteTool("snippet_delete"),
        )

    private fun createListTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description =
                "List stored prompt/command snippets, optionally filtered to a single tag. Returns at most " +
                    "'limit' entries (default $DEFAULT_LIST_LIMIT) starting at 'offset'; 'total' says how many match.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "tag": { "type": "string", "description": "Optional tag to filter by (case-insensitive)" },
                        "limit": { "type": "integer", "description": "Maximum entries to return, 1 to $MAX_SNIPPETS" },
                        "offset": { "type": "integer", "description": "Entries to skip, for paging" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleList(args) },
            readOnly = true,
        )

    private fun createGetTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "Get one snippet, including its full body, by id.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "id": { "type": "string", "description": "Snippet id" }
                    },
                    "required": ["id"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleGet(args) },
            readOnly = true,
        )

    private fun createSaveTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description =
                "Create a snippet, or update an existing one when 'id' is supplied. 'tags' is a " +
                    "comma-separated list.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "id": { "type": "string", "description": "Existing snippet id to update; omit to create" },
                        "title": { "type": "string", "maxLength": $MAX_TITLE_CHARS, "description": "Short human-readable title" },
                        "body": { "type": "string", "maxLength": $MAX_BODY_CHARS, "description": "The prompt or command text" },
                        "tags": { "type": "string", "maxLength": $MAX_TAGS_CHARS, "description": "Comma-separated tags" }
                    },
                    "required": ["title", "body"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleSave(args) },
            readOnly = false,
        )

    private fun createDeleteTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "Delete a snippet by id.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "id": { "type": "string", "description": "Snippet id" }
                    },
                    "required": ["id"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleDelete(args) },
            readOnly = false,
        )

    /**
     * Paged, because this is read-only and so allowed without asking. Bodies were already left out;
     * the count was not bounded by anything, since the library had no size at all.
     */
    private fun handleList(args: McpToolArgs): McpToolResult {
        val tag = args.string("tag")
        val limit = (args.int("limit") ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_SNIPPETS)
        val offset = (args.int("offset") ?: 0).coerceAtLeast(0)
        val matching =
            if (tag.isNullOrBlank()) {
                SnippetLibraryManager.snippets.value
            } else {
                SnippetLibraryManager.byTag(tag)
            }
        val page = matching.drop(offset).take(limit)
        val response =
            buildJsonObject {
                put("success", true)
                put("total", matching.size)
                put("offset", offset)
                put("returned", page.size)
                put(
                    "snippets",
                    buildJsonArray {
                        page.forEach { add(summaryJson(it)) }
                    },
                )
            }
        return McpToolResult(response.toString())
    }

    @Suppress("ReturnCount")
    private fun handleGet(args: McpToolArgs): McpToolResult {
        val id = args.string("id")
        if (id.isNullOrBlank()) {
            return McpToolResult("id is required", isError = true)
        }
        val snippet =
            SnippetLibraryManager.get(id)
                ?: return McpToolResult("Snippet '$id' not found", isError = true)
        return McpToolResult(fullJson(snippet).toString())
    }

    @Suppress("ReturnCount")
    private suspend fun handleSave(args: McpToolArgs): McpToolResult {
        val title = args.string("title")
        val body = args.string("body")
        if (title.isNullOrBlank()) {
            return McpToolResult("title is required", isError = true)
        }
        if (body == null) {
            return McpToolResult("body is required", isError = true)
        }
        // This tool is the library's only writer. Refused with the limit named rather than cut, so
        // a caller that sent too much learns it instead of finding its prompt silently truncated.
        tooLong(args)?.let { return McpToolResult(it, isError = true) }
        // Absent 'tags' means "keep the existing set" on update; an explicit
        // empty string clears it.
        val tags = args.string("tags")?.let(::parseTags)
        val id = args.string("id")

        if (id.isNullOrBlank() && SnippetLibraryManager.snippets.value.size >= MAX_SNIPPETS) {
            return McpToolResult(
                "The snippet library is full ($MAX_SNIPPETS snippets). Delete one, or update an " +
                    "existing snippet by passing its 'id'.",
                isError = true,
            )
        }
        val saved =
            if (id.isNullOrBlank()) {
                SnippetLibraryManager.add(title, body, tags.orEmpty())
            } else {
                SnippetLibraryManager.update(id, title, body, tags)
                    ?: return McpToolResult("Snippet '$id' not found; omit 'id' to create a new one", isError = true)
            }
        return McpToolResult(fullJson(saved).toString())
    }

    private suspend fun handleDelete(args: McpToolArgs): McpToolResult {
        val id = args.string("id")
        if (id.isNullOrBlank()) {
            return McpToolResult("id is required", isError = true)
        }
        return if (SnippetLibraryManager.remove(id)) {
            McpToolResult(
                buildJsonObject {
                    put("success", true)
                    put("id", id)
                    put("deleted", true)
                }.toString(),
            )
        } else {
            McpToolResult("Snippet '$id' not found", isError = true)
        }
    }

    private fun tooLong(args: McpToolArgs): String? =
        listOf(
            "title" to MAX_TITLE_CHARS,
            "body" to MAX_BODY_CHARS,
            "tags" to MAX_TAGS_CHARS,
        ).firstNotNullOfOrNull { (field, max) ->
            val length = args.string(field)?.length ?: 0
            if (length > max) "$field is $length characters; the limit is $max" else null
        }

    /** Comma-separated tags to a trimmed, non-empty list. */
    private fun parseTags(s: String?): List<String> = s.orEmpty().split(',').mapNotNull { it.trim().ifEmpty { null } }

    /** The list view omits [Snippet.body] so a long-body library does not flood the agent's context. */
    private fun summaryJson(snippet: Snippet) =
        buildJsonObject {
            put("id", snippet.id)
            put("title", snippet.title)
            put("tags", snippet.tags.joinToString(","))
            put("createdAt", snippet.createdAt)
            put("updatedAt", snippet.updatedAt)
        }

    private fun fullJson(snippet: Snippet) =
        buildJsonObject {
            put("success", true)
            put("id", snippet.id)
            put("title", snippet.title)
            put("body", snippet.body)
            put("tags", snippet.tags.joinToString(","))
            put("createdAt", snippet.createdAt)
            put("updatedAt", snippet.updatedAt)
        }
}
