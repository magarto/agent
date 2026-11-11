package com.past9.phoneaos.triggers

import android.content.Context
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.data.QuietSaver
import com.past9.phoneaos.data.TriggerDao
import org.json.JSONObject
import java.io.File

/**
 * Named text blocks several routines share (GitHub issue #53): a prompt says `{{block:name}}` and gets the block's
 * text right before it runs, so a rule repeated across routines lives in one place. Kept in a JSON file, not in Room:
 * a schema change would wipe the user's database.
 */
class RoutineBlocks(private val file: File) {
    fun all(): Map<String, String> = synchronized(LOCK) {
        val o = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        o.keys().asSequence().associateWith { o.getString(it) }.toSortedMap()
    }

    fun save(name: String, text: String) = synchronized(LOCK) { write(all() + (name to text)) }

    fun delete(name: String): Boolean = synchronized(LOCK) { all().let { m -> (name in m).also { if (it) write(m - name) } } }

    private fun write(m: Map<String, String>) = QuietSaver.write(file, JSONObject(m).toString())

    companion object {
        private val LOCK = Any()
        private val REF = Regex("""\{\{block:([^{}]*)\}\}""")
        val NAME = Regex("[A-Za-z0-9_-]{1,40}")

        fun of(context: Context) = RoutineBlocks(File(context.filesDir, "routine_blocks.json"))

        /** The block names a prompt uses, in order, once each. */
        fun usedIn(prompt: String): List<String> = REF.findAll(prompt).map { it.groupValues[1].trim() }.distinct().toList()

        /** One level only: text that came from a block is not scanned again, so blocks can't loop. */
        fun expand(prompt: String, blocks: Map<String, String>): String =
            REF.replace(prompt) { m -> m.groupValues[1].trim().let { blocks[it] ?: "(block $it not found)" } }

        fun expand(context: Context, prompt: String): String = if (usedIn(prompt).isEmpty()) prompt else expand(prompt, of(context).all())
    }
}

class BlockSaveTool(private val blocks: RoutineBlocks) : Tool {
    override val spec = ToolSpec("block_save", "Save a shared text block for routines (creates it, or replaces the text of one with the same name). " +
        "Use it when the same rules or context repeat across several routines (how to sort the inbox, who counts as important, the reply style): " +
        "write them once here and put {{block:<name>}} in each routine's prompt. The block's text is pasted in right before the routine runs, so changing the block changes every routine that uses it. " +
        "One level only: a {{block:...}} inside a block's own text is left as it is, not expanded.",
        schema(listOf("name", "text"), "name" to str("Letters, digits, _ or -, up to 40, e.g. 'inbox_rules'"), "text" to str("The whole text of the block")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val name = input.optString("name").trim()
        if (!RoutineBlocks.NAME.matches(name)) return "Not saved: the name must be 1 to 40 letters, digits, _ or -."
        val text = input.optString("text")
        if (text.isBlank()) return "Not saved: the text is empty."
        val existed = name in blocks.all()
        blocks.save(name, text)
        return "Block \"$name\" ${if (existed) "replaced" else "saved"}. Use it in a routine prompt as {{block:$name}}."
    }
}

class BlockListTool(private val blocks: RoutineBlocks, private val dao: TriggerDao) : Tool {
    override val spec = ToolSpec("block_list", "List the shared routine text blocks: each one's name, size, which routines use it, and its whole text.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val all = blocks.all()
        val users = dao.list().flatMap { t -> RoutineBlocks.usedIn(t.prompt).map { it to "#${t.id} ${t.name}" } }.groupBy({ it.first }, { it.second })
        val lines = all.map { (n, text) -> "$n (${text.length} chars), used by: ${users[n]?.joinToString() ?: "no routine"}\n$text" }
        // A routine that names a block nobody saved runs with a "not found" note: show it so it can be fixed.
        val missing = (users.keys - all.keys).map { "$it: NOT SAVED, but used by ${users[it]!!.joinToString()}" }
        return (lines + missing).joinToString("\n\n").ifBlank { "No blocks." }
    }
}

class BlockDeleteTool(private val blocks: RoutineBlocks, private val dao: TriggerDao) : Tool {
    override val spec = ToolSpec("block_delete", "Delete a shared routine text block by name. Refused while a routine still uses it: " +
        "first take {{block:<name>}} out of those routines with routine_update (or paste the text in), then delete it.",
        schema(listOf("name"), "name" to str("The block's name")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val name = input.optString("name").trim()
        // Deleting a block in use would silently change what those routines do on their next run.
        val users = dao.list().filter { name in RoutineBlocks.usedIn(it.prompt) }
        if (users.isNotEmpty()) return "Not deleted: still used by ${users.joinToString { "#${it.id} ${it.name}" }}. Change those routines first."
        return if (blocks.delete(name)) "Block \"$name\" deleted." else "No block called \"$name\"."
    }
}
