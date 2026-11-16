package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.tools.routineSpecError
import com.past9.phoneaos.triggers.BluetoothTrigger
import com.past9.phoneaos.triggers.EventGate
import com.past9.phoneaos.triggers.Places
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Issue #53: routines that run on arriving at or leaving a saved place, and when a paired Bluetooth device connects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceBluetoothTriggersTest {
    @Test fun placeSpecNeedsANameAndArriveOrLeave() {
        assertEquals("Home" to true, Places.parseSpec("Home|arrive"))
        assertEquals("Main office" to false, Places.parseSpec(" Main office | LEAVE "))
        assertNull(Places.parseSpec("Home"))
        assertNull(Places.parseSpec("Home|enter"))
        assertNull(Places.parseSpec("|arrive"))
        assertNull(routineSpecError("place", "Home|leave"))
        assertNotNull(routineSpecError("place", "Home"))
    }

    @Test fun placeMatchesByNameAnyCaseAndDirection() {
        assertTrue(Places.matches("home|arrive", "Home", entering = true))
        assertFalse(Places.matches("Home|arrive", "Home", entering = false))
        assertTrue(Places.matches("Home|leave", "HOME", entering = false))
        assertFalse(Places.matches("Gym|arrive", "Home", entering = true))
    }

    @Test fun bluetoothSpecDefaultsToConnect() {
        assertEquals("Car" to true, BluetoothTrigger.parseSpec("Car"))
        assertEquals("Car" to true, BluetoothTrigger.parseSpec("Car|connect"))
        assertEquals("Headphones" to false, BluetoothTrigger.parseSpec("Headphones | disconnect"))
        assertNull(BluetoothTrigger.parseSpec("Car|later"))
        assertNull(BluetoothTrigger.parseSpec("  |connect"))
        assertNull(routineSpecError("bluetooth", "AA:BB:CC:DD:EE:FF"))
        assertNotNull(routineSpecError("bluetooth", ""))
    }

    @Test fun bluetoothMatchesNameOrAddressAndDirection() {
        val addr = "AA:BB:CC:DD:EE:FF"
        assertTrue(BluetoothTrigger.matches("car", "Car", addr, connected = true))
        assertTrue(BluetoothTrigger.matches("aa:bb:cc:dd:ee:ff|connect", null, addr, connected = true))
        assertFalse(BluetoothTrigger.matches("Car", "Car", addr, connected = false))
        assertTrue(BluetoothTrigger.matches("Car|disconnect", "Car", addr, connected = false))
        assertFalse(BluetoothTrigger.matches("Car", null, addr, connected = true))
        assertFalse(BluetoothTrigger.matches("Headphones", "Car", addr, connected = true))
    }

    @Test fun gateLetsOneEventThroughPerWindow() {
        val key = "test|" + System.nanoTime()
        assertTrue(EventGate.pass(key, 600_000, now = 1_000_000))
        assertFalse(EventGate.pass(key, 600_000, now = 1_000_000 + 599_999))
        assertTrue(EventGate.pass(key, 600_000, now = 1_000_000 + 600_000))
        assertTrue("another key is its own", EventGate.pass("$key|other", 600_000, now = 1_000_000 + 1))
    }

    @Test fun placesAreSavedReplacedByNameAndDeleted() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Places.save(ctx, Places.Place("Home", 10.5, -20.25))
        Places.save(ctx, Places.Place("Gym", 1.0, 2.0, 300))
        Places.save(ctx, Places.Place("home", 11.0, -21.0, 200))
        assertEquals(listOf(Places.Place("Gym", 1.0, 2.0, 300), Places.Place("home", 11.0, -21.0, 200)), Places.load(ctx))
        assertTrue(Places.delete(ctx, "HOME"))
        assertFalse(Places.delete(ctx, "Nowhere"))
        assertEquals(listOf("Gym"), Places.load(ctx).map { it.name })
        assertEquals(Places.DEFAULT_RADIUS_M, Places.Place("X", 0.0, 0.0).radiusM)
    }
}
