package com.past9.phoneaos.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import com.past9.phoneaos.App
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.system.PermissionBroker
import com.past9.phoneaos.tools.PhoneLocation
import com.past9.phoneaos.tools.PhoneLocationTool
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * "place" routines: run when the phone arrives at or leaves a saved place. Android's own proximity alerts do the
 * watching (no Play services, no tracking by this app); only the place definitions are kept, in a file outside Room.
 */
object Places {
    const val KIND = "place"
    const val DEFAULT_RADIUS_M = 150
    private const val DEBOUNCE_MS = 10 * 60_000L

    data class Place(val name: String, val lat: Double, val lon: Double, val radiusM: Int = DEFAULT_RADIUS_M)

    /** "Home|arrive" -> ("Home", true); "Home|leave" -> ("Home", false); anything else null. */
    fun parseSpec(spec: String): Pair<String, Boolean>? {
        val name = spec.substringBeforeLast('|', "").trim()
        val arrive = when (spec.substringAfterLast('|', "").trim().lowercase()) { "arrive" -> true; "leave" -> false; else -> return null }
        return if (name.isEmpty()) null else name to arrive
    }

    fun matches(spec: String, place: String, entering: Boolean): Boolean =
        parseSpec(spec)?.let { (name, arrive) -> name.equals(place, ignoreCase = true) && arrive == entering } ?: false

    private fun file(context: Context) = File(context.filesDir, "places.json")

    fun load(context: Context): List<Place> = runCatching {
        val a = JSONArray(file(context).readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optInt("radius", DEFAULT_RADIUS_M)) } }
    }.getOrDefault(emptyList())

    private fun write(context: Context, places: List<Place>) = file(context).writeText(JSONArray(places.map {
        JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("radius", it.radiusM)
    }).toString())

    /** Adds the place, or replaces the one with the same name (any case). */
    fun save(context: Context, p: Place) = write(context, load(context).filterNot { it.name.equals(p.name, true) } + p)

    /** False when there was no such place. Its alert goes with it. */
    fun delete(context: Context, name: String): Boolean {
        val all = load(context)
        val gone = all.filter { it.name.equals(name, true) }
        if (gone.isEmpty()) return false
        gone.forEach { disarm(context, it.name) }
        write(context, all - gone.toSet())
        return true
    }

    /** Proximity alerts need a precise location, and they only fire for a closed app with "Allow all the time". */
    fun permitted(context: Context) = PermissionBroker.granted(context, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) && PhoneLocation.hasBackground(context)

    private fun intent(context: Context, name: String) =
        Intent(context, PlaceReceiver::class.java).setData(Uri.fromParts("place", name.lowercase(), null))

    // Mutable: the system adds KEY_PROXIMITY_ENTERING to it.
    private fun pending(context: Context, name: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, intent(context, name), flags or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))

    private fun disarm(context: Context, name: String) {
        val pi = pending(context, name, PendingIntent.FLAG_NO_CREATE) ?: return
        runCatching { context.getSystemService(LocationManager::class.java).removeProximityAlert(pi) }
        pi.cancel()
    }

    private val lock = Mutex()

    /**
     * Give every place an enabled routine uses an alert, and take it from the rest. An alert already armed is left
     * alone (it lives in the system until a reboot, an update or a force stop), except [refresh], whose spot changed.
     */
    @SuppressLint("MissingPermission")
    suspend fun arm(context: Context, refresh: String? = null) = lock.withLock {
        val wanted = App.graph(context).db.triggers().list().filter { it.enabled && it.kind == KIND }.mapNotNull { parseSpec(it.spec)?.first?.lowercase() }.toSet()
        val ok = permitted(context)
        val lm = context.getSystemService(LocationManager::class.java)
        for (p in load(context)) {
            val armed = pending(context, p.name, PendingIntent.FLAG_NO_CREATE) != null
            if (p.name.lowercase() !in wanted || !ok) { if (armed) disarm(context, p.name); continue }
            if (armed && !p.name.equals(refresh, true)) continue
            disarm(context, p.name)
            val pi = pending(context, p.name, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
            if (runCatching { lm.addProximityAlert(p.lat, p.lon, p.radiusM.toFloat(), -1, pi) }.isFailure) { pi.cancel(); continue }
            // Armed while already inside, Android reports an arrival at once: that is not one.
            EventGate.pass("place|${p.name.lowercase()}|true", DEBOUNCE_MS)
        }
        // A location_get would otherwise hand the permission back when the app closes, and the alerts would go quiet.
        if (ok && wanted.isNotEmpty()) runCatching { App.graph(context).settings.setExtra(PhoneLocation.KEEP_KEY, "1") }
        EventGate.markBlocked(context, KIND, context.getString(com.past9.phoneaos.R.string.place_needs_location), blocked = !ok)
    }

    /** What the agent should hear about a new place routine: the place is unknown, or location is not allowed. */
    fun warning(context: Context, spec: String): String? {
        val name = parseSpec(spec)?.first ?: return null
        return when {
            load(context).none { it.name.equals(name, true) } -> "There is no saved place called \"$name\" yet: save it with place_save, or it never fires."
            !permitted(context) -> "Location is not allowed all the time, so it cannot fire yet. Ask the user to set Location to 'Allow all the time' and 'Use precise location' for the Agent app (Settings, Apps, Agent, Permissions, Location)."
            else -> null
        }
    }

    internal fun onAlert(context: Context, key: String, entering: Boolean): kotlinx.coroutines.Job? {
        val place = load(context).firstOrNull { it.name.equals(key, true) } ?: return null
        if (!EventGate.pass("place|${key.lowercase()}|$entering", DEBOUNCE_MS)) return null
        return EventGate.fire(context, KIND, if (entering) "Arrived at ${place.name}. " else "Left ${place.name}. ") { matches(it, place.name, entering) }
    }
}

class PlaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.data?.schemeSpecificPart ?: return
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val done = goAsync()
        val job = Places.onAlert(context, key, intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false))
        if (job == null) done.finish() else job.invokeOnCompletion { done.finish() }
    }
}

/** Shared by the event routines (places, Bluetooth): debounce in memory only, so no history of where or when is kept. */
object EventGate {
    private val last = ConcurrentHashMap<String, Long>()

    /** True (and remembered) when [key] has not passed within [windowMs]. */
    fun pass(key: String, windowMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        var ok = false
        last.compute(key) { _, prev -> if (prev == null || now - prev >= windowMs) { ok = true; now } else prev }
        return ok
    }

    /** Run every enabled routine of [kind] whose spec [match]es, through the same worker as notification routines. */
    fun fire(context: Context, kind: String, note: String, match: (String) -> Boolean): kotlinx.coroutines.Job {
        val g = App.graph(context)
        return g.scope.launch {
            g.db.triggers().list().filter { it.enabled && it.kind == kind && match(it.spec) }.forEach { Routines.runNow(context, it.id, note) }
        }
    }

    /** Show [note] on the routines of [kind] while they cannot fire, and take it off once they can. */
    suspend fun markBlocked(context: Context, kind: String, note: String, blocked: Boolean) {
        val dao = App.graph(context).db.triggers()
        dao.list().filter { it.enabled && it.kind == kind }.forEach { t ->
            if (blocked && t.lastResult != note) dao.upsert(t.copy(lastResult = note))
            else if (!blocked && t.lastResult == note) dao.upsert(t.copy(lastResult = ""))
        }
    }
}

class PlaceSaveTool(private val context: Context, private val here: Tool = PhoneLocationTool(context)) : Tool {
    override val spec = ToolSpec("place_save",
        "Save a named place (or move one: same name replaces it) for 'place' routines, which run on arriving at or leaving it. " +
            "Give lat and lon, or here=true to use where the phone is now (asks the user for location if needed).",
        schema(listOf("name"), "name" to str("Short name, e.g. 'Home' or 'Office'"),
            "lat" to JSONObject().put("type", "number").put("description", "Latitude"),
            "lon" to JSONObject().put("type", "number").put("description", "Longitude"),
            "here" to com.past9.phoneaos.agent.bool("true: use the phone's current position"),
            "radius" to int("Metres around it that count as there (default ${Places.DEFAULT_RADIUS_M}, 100 to 5000)")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val name = input.optString("name").trim().takeIf { it.isNotEmpty() && '|' !in it } ?: return "Not saved: give a name without '|'."
        val (lat, lon) = if (input.optBoolean("here")) {
            val fix = runCatching { JSONObject(here.run(JSONObject().put("max_age", 60).put("reason", "save this spot as $name"), ctx)) }
                .getOrElse { return "Not saved: no position (${it.message})." }
            fix.getDouble("lat") to fix.getDouble("lng")
        } else {
            val la = input.optDouble("lat"); val lo = input.optDouble("lon")
            if (la.isNaN() || lo.isNaN() || la !in -90.0..90.0 || lo !in -180.0..180.0) return "Not saved: give lat and lon, or here=true."
            la to lo
        }
        val radius = input.optInt("radius", Places.DEFAULT_RADIUS_M).coerceIn(100, 5000)
        Places.save(context, Places.Place(name, lat, lon, radius))
        Places.arm(context, refresh = name)
        return "Saved place \"$name\" (%.5f, %.5f, %d m). Use it in routine_create with kind 'place' and spec '$name|arrive' or '$name|leave'.".format(java.util.Locale.US, lat, lon, radius) +
            if (Places.permitted(context)) "" else " Location is not allowed all the time yet, so place routines cannot fire: ask the user to allow it."
    }
}

class PlaceListTool(private val context: Context) : Tool {
    override val spec = ToolSpec("place_list", "List the saved places that 'place' routines can use.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String =
        Places.load(context).joinToString("\n") { "${it.name}: %.5f, %.5f, %d m".format(java.util.Locale.US, it.lat, it.lon, it.radiusM) }.ifBlank { "No saved places." }
}

class PlaceDeleteTool(private val context: Context) : Tool {
    override val spec = ToolSpec("place_delete", "Delete a saved place. Routines that use it stop firing (change or delete them too).", schema(listOf("name"), "name" to str("The place's name")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String =
        if (Places.delete(context, input.optString("name").trim())) "Deleted." else "No saved place called \"${input.optString("name")}\"."
}
