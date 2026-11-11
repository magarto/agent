package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import org.json.JSONObject

/**
 * Secrets the user stored for the agent (Settings): the agent writes {{secret:name}} in a tool's text and the tool puts
 * the value in at the last moment. Values never reach the model: any tool result that holds one is redacted back to
 * its placeholder before the model, the chat or a log sees it.
 */
object AgentSecrets {
    private val REF = Regex("""\{\{secret:([^}]*)}}""")
    /** The stored values (name -> value), read fresh each call so a deleted secret stops working at once. Set by the app at start. */
    @Volatile var values: () -> Map<String, String> = { emptyMap() }

    /** A name as stored: lower case, spaces and dashes as underscores. Null when nothing usable is left. */
    fun cleanName(name: String): String? = name.trim().lowercase().replace(Regex("[\\s-]+"), "_").takeIf { it.matches(Regex("[a-z0-9_]{1,40}")) }

    fun hasRef(text: String) = REF.containsMatchIn(text)

    /** [text] with each {{secret:name}} replaced by its value. Throws, naming the secrets there are, when one is unknown. */
    fun fill(text: String, values: Map<String, String> = this.values()): String =
        if (!hasRef(text)) text else REF.replace(text) { m -> values[cleanName(m.groupValues[1]) ?: ""] ?: throw IllegalArgumentException(unknown(m.groupValues[1], values.keys)) }

    /** The same for every string value in a tool's input, on a copy (the call itself keeps the placeholders). */
    fun fill(input: JSONObject, values: Map<String, String> = this.values()): JSONObject = JSONObject(input.toString()).apply {
        keys().asSequence().toList().forEach { k -> (opt(k) as? String)?.let { put(k, fill(it, values)) } }
    }

    /** [text] with every stored value put back as its placeholder, longest first so one value inside another can't show through. */
    fun redact(text: String, values: Map<String, String> = this.values()): String =
        values.entries.filter { it.value.isNotEmpty() }.sortedByDescending { it.value.length }.fold(text) { t, (name, v) -> t.replace(v, "{{secret:$name}}") }

    /** The environment variable run_code gets a secret in. */
    fun envName(name: String) = "SECRET_" + name.uppercase()

    fun unknown(name: String, names: Collection<String>) =
        "No secret called \"$name\". Available: ${names.sorted().joinToString().ifEmpty { "none (the user adds them in Settings > Automation > Secrets)" }}."

    const val HOW = "Write {{secret:name}} where the value goes; it is filled in at the last moment and you never see it."
}

class SecretsListTool(private val notes: () -> Map<String, String>) : Tool {
    override val spec = ToolSpec("secrets_list", "The secrets the user stored for you (passwords, PINs, API keys): each one's name and what it is for, never its value. " +
        "Use one by writing {{secret:name}} in the text of browser_type, screen_type or a web app tool's inputs; for run_code list the names in `secrets` and read process.env.SECRET_<NAME>. " +
        "You never see a value: any tool output that contains one shows {{secret:name}} instead.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String = notes().toSortedMap().ifEmpty { return "No secrets yet. The user adds them in Settings > Automation > Secrets." }
        .entries.joinToString("\n") { (n, note) -> "{{secret:$n}}" + if (note.isNotBlank()) ": $note" else "" }
}
