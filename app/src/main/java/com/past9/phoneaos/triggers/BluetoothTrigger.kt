package com.past9.phoneaos.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.past9.phoneaos.App
import com.past9.phoneaos.R
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.system.PermissionBroker
import org.json.JSONObject

/**
 * "bluetooth" routines: run when a paired device (a car, headphones) connects or disconnects. The receiver is in the
 * manifest (ACL_CONNECTED/DISCONNECTED are exempt from the implicit-broadcast limits) but switched off until a
 * Bluetooth routine exists, so nobody else's phone wakes the app on every connection.
 */
object BluetoothTrigger {
    const val KIND = "bluetooth"
    private const val DEBOUNCE_MS = 2 * 60_000L

    /** "Car" or "Car|connect" -> ("Car", true); "Car|disconnect" -> ("Car", false); a bad direction or no device null. */
    fun parseSpec(spec: String): Pair<String, Boolean>? {
        val device = (if ('|' in spec) spec.substringBeforeLast('|') else spec).trim()
        val connect = if ('|' !in spec) true else when (spec.substringAfterLast('|').trim().lowercase()) { "connect" -> true; "disconnect" -> false; else -> return null }
        return if (device.isEmpty()) null else device to connect
    }

    /** The spec names the device by its name or its address, either case. */
    fun matches(spec: String, name: String?, address: String, connected: Boolean): Boolean =
        parseSpec(spec)?.let { (d, c) -> c == connected && (d.equals(address, true) || (name != null && d.equals(name, true))) } ?: false

    /** Android 12+ asks for "Nearby devices" before an app may see paired devices' names. */
    fun permitted(context: Context) = Build.VERSION.SDK_INT < 31 || PermissionBroker.granted(context, arrayOf(Manifest.permission.BLUETOOTH_CONNECT))

    /** (name, address) of every paired device, or null when Bluetooth can't be read. */
    @SuppressLint("MissingPermission")
    fun bonded(context: Context): List<Pair<String, String>>? = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.map { (it.name ?: "") to it.address }
    }.getOrNull()

    /** The receiver is on only while an enabled Bluetooth routine exists. */
    suspend fun sync(context: Context) {
        val wanted = App.graph(context).db.triggers().list().any { it.enabled && it.kind == KIND }
        context.packageManager.setComponentEnabledSetting(ComponentName(context, BluetoothReceiver::class.java),
            if (wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        EventGate.markBlocked(context, KIND, context.getString(R.string.bluetooth_needs_permission), blocked = !permitted(context))
    }

    /** What the agent should hear about a new Bluetooth routine: no permission, or no paired device by that name. */
    fun warning(context: Context, spec: String): String? {
        val device = parseSpec(spec)?.first ?: return null
        if (!permitted(context)) return "The 'Nearby devices' permission is not allowed, so it cannot fire yet. Call bluetooth_devices to ask the user for it."
        val all = bonded(context) ?: return null
        return if (all.none { it.first.equals(device, true) || it.second.equals(device, true) })
            "No paired device is called \"$device\" (paired: ${all.joinToString { it.first.ifBlank { it.second } }.ifBlank { "none" }}). Check the name with bluetooth_devices." else null
    }

    @SuppressLint("MissingPermission")
    internal fun onEvent(context: Context, device: BluetoothDevice, connected: Boolean): kotlinx.coroutines.Job? {
        if (!EventGate.pass("bt|${device.address}|$connected", DEBOUNCE_MS)) return null
        val name = runCatching { device.name }.getOrNull()
        val label = name?.takeIf { it.isNotBlank() } ?: device.address
        return EventGate.fire(context, KIND, if (connected) "Bluetooth device $label just connected. " else "Bluetooth device $label just disconnected. ") {
            matches(it, name, device.address, connected)
        }
    }
}

class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) { BluetoothDevice.ACTION_ACL_CONNECTED -> true; BluetoothDevice.ACTION_ACL_DISCONNECTED -> false; else -> return }
        @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val done = goAsync()
        val job = BluetoothTrigger.onEvent(context, device, connected)
        if (job == null) done.finish() else job.invokeOnCompletion { done.finish() }
    }
}

class BluetoothDevicesTool(private val context: Context) : Tool {
    override val spec = ToolSpec("bluetooth_devices",
        "List the phone's paired Bluetooth devices (name and address), to pick one for a 'bluetooth' routine. Asks the user for the 'Nearby devices' permission if needed.", schema())

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        if (!BluetoothTrigger.permitted(context)) {
            val ok = PermissionBroker.request(context, arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) {
                ctx.notifyBlocking(context.getString(R.string.bluetooth_allow_title), context.getString(R.string.bluetooth_allow_body))
            }
            if (!ok) return "The 'Nearby devices' permission wasn't granted, so paired devices can't be listed and Bluetooth routines can't fire. Ask the user to allow it for the Agent app (Settings, Apps, Agent, Permissions, Nearby devices)."
            Routines.syncEvents(context)
        }
        val all = BluetoothTrigger.bonded(context) ?: return "Bluetooth can't be read on this phone."
        return all.joinToString("\n") { "${it.first.ifBlank { "(no name)" }} - ${it.second}" }.ifBlank { "No paired devices (or Bluetooth is off)." }
    }
}
