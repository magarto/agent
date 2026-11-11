package com.past9.phoneaos.triggers

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Per-routine options kept beside the routine's row, one JSON file per routine id: a new Room column would mean a
 * schema change, and that wipes the whole database (fallbackToDestructiveMigration).
 *
 * @param silent only tell the user when something needs them: a run that finds nothing replies QUIET and posts nothing.
 */
data class RoutineOptions(val silent: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("silent", silent)

    companion object {
        /** Told to a silent routine's run, after its prompt. */
        val SILENT_NOTE = "\n\n(Only tell the user when something needs them. If nothing does, reply with exactly ${WakePlan.QUIET} and nothing else: they will not be told.)"

        fun fromJson(j: JSONObject) = RoutineOptions(silent = j.optBoolean("silent"))

        fun dir(context: Context) = File(context.filesDir, "routine_options")
        fun get(dir: File, id: Long): RoutineOptions =
            runCatching { fromJson(JSONObject(File(dir, "$id.json").readText())) }.getOrDefault(RoutineOptions())
        fun set(dir: File, id: Long, o: RoutineOptions) {
            dir.mkdirs()
            File(dir, "$id.json").writeText(o.toJson().toString())
        }
        fun remove(dir: File, id: Long) { File(dir, "$id.json").delete() }
    }
}
