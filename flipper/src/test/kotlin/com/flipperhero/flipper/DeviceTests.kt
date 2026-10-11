package com.flipperhero.flipper

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.system.UpdateResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** App control, storage and system commands against [SimulatedFlipper]. Ported from Swift `DeviceTests`. */
class DeviceTests {

    // MARK: App control

    @Test
    fun transmitOpensLoadsPressesReleasesAndCloses(): Unit = runBlocking {
        val flipper = SimulatedFlipper(mapOf("/ext/subghz/Garage.sub" to "Filetype: Flipper SubGhz Key File"))
        val client = flipper.client()
        client.transmitOnce(FlipperApp.SUB_GHZ, "/ext/subghz/Garage.sub", hold = 1.milliseconds)
        assertEquals(
            listOf("stat /ext/subghz/Garage.sub", "appStart Sub-GHz RPC",
                "appLoad /ext/subghz/Garage.sub", "appPress ", "appRelease", "appExit"),
            flipper.log,
        )
    }

    @Test
    fun infraredPassesTheButtonName(): Unit = runBlocking {
        val flipper = SimulatedFlipper(mapOf("/ext/infrared/TV.ir" to "Filetype: IR signals file"))
        val client = flipper.client()
        client.transmitOnce(FlipperApp.INFRARED, "/ext/infrared/TV.ir", button = "Power", hold = 1.milliseconds)
        assertTrue(flipper.log.contains("appPress Power"))
    }

    @Test
    fun wrongFileTypeIsRejectedBeforeTouchingTheDevice(): Unit = runBlocking {
        val flipper = SimulatedFlipper(mapOf("/ext/nfc/Card.nfc" to "x"))
        val client = flipper.client()
        assertThrowsSuspend<FlipperException.InvalidPath> {
            client.transmitOnce(FlipperApp.SUB_GHZ, "/ext/nfc/Card.nfc", hold = 1.milliseconds)
        }
        assertTrue(flipper.log.isEmpty())
    }

    @Test
    fun missingFileStopsBeforeStartingTheApp(): Unit = runBlocking {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        assertThrowsSuspend<FlipperException.Rpc> { client.emulate(FlipperApp.NFC, "/ext/nfc/Missing.nfc") }
        assertEquals(listOf("stat /ext/nfc/Missing.nfc"), flipper.log)
    }

    @Test
    fun failedLoadClosesTheApp(): Unit = runBlocking {
        val flipper = SimulatedFlipper(mapOf("/ext/lfrfid/Tag.rfid" to "x"))
        flipper.failures["appLoad"] = CommandStatus.ERROR_INVALID_PARAMETERS
        val client = flipper.client()
        assertThrowsSuspend<FlipperException.Rpc> { client.emulate(FlipperApp.RFID, "/ext/lfrfid/Tag.rfid") }
        assertEquals("appExit", flipper.log.last())
    }

    @Test
    fun failedPressStillClosesTheApp(): Unit = runBlocking {
        val flipper = SimulatedFlipper(mapOf("/ext/subghz/Gate.sub" to "x"))
        flipper.failures["appPress"] = CommandStatus.ERROR_INVALID_PARAMETERS
        val client = flipper.client()
        assertThrowsSuspend<FlipperException.Rpc> {
            client.transmitOnce(FlipperApp.SUB_GHZ, "/ext/subghz/Gate.sub", hold = 1.milliseconds)
        }
        assertEquals("appExit", flipper.log.last())
        assertFalse(flipper.log.contains("appRelease"))
    }

    @Test
    fun emulationKeepsTheAppOpen(): Unit = runBlocking {
        val flipper = SimulatedFlipper(mapOf("/ext/ibutton/Door.ibtn" to "x"))
        val client = flipper.client()
        client.emulate(FlipperApp.IBUTTON, "/ext/ibutton/Door.ibtn")
        assertEquals(
            listOf("stat /ext/ibutton/Door.ibtn", "appStart iButton RPC", "appLoad /ext/ibutton/Door.ibtn"),
            flipper.log,
        )
        client.exitApp()
        assertEquals("appExit", flipper.log.last())
    }

    @Test
    fun badKbIsOpenedWithTheScriptButNeverStarted(): Unit = runBlocking {
        val flipper = SimulatedFlipper(mapOf("/ext/badusb/Hello.txt" to "STRING hi"))
        val client = flipper.client()
        client.loadBadKeyboardScript("/ext/badusb/Hello.txt")
        assertEquals(listOf("stat /ext/badusb/Hello.txt", "appStart Bad KB /ext/badusb/Hello.txt"), flipper.log)
    }

    @Test
    fun appErrorIsReported(): Unit = runBlocking {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        assertNull(client.appError())
        flipper.appErrorText = "Invalid file"
        assertEquals("Invalid file", client.appError())
    }

    // MARK: System

    @Test
    fun rebootModesAndAlert(): Unit = runBlocking {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        client.reboot()
        client.rebootIntoUpdater()
        client.playAlert()
        assertEquals(listOf("reboot os", "reboot update", "alert"), flipper.log)
    }

    @Test
    fun rebootToleratesTheLinkDropping(): Unit = runBlocking {
        val flipper = SimulatedFlipper()
        flipper.transport.responder = { emptyList() }
        val client = flipper.client()
        flipper.transport.close()
        client.reboot()
    }

    @Test
    fun updateRequestResult(): Unit = runBlocking {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        val ok = client.requestUpdate("/ext/update/f7-update-mntm-012/update.fuf")
        assertEquals(FlipperUpdateResult.Ok, ok)
        flipper.updateResult = UpdateResponse.UpdateResultCode.TargetMismatch
        val rejected = client.requestUpdate("/ext/update/f7-update-mntm-012/update.fuf")
        assertEquals(FlipperUpdateResult.Rejected("TargetMismatch"), rejected)
        assertEquals("update /ext/update/f7-update-mntm-012/update.fuf", flipper.log.first())
    }

    // MARK: Storage

    @Test
    fun writeSplitsIntoChunksAndRoundTrips(): Unit = runBlocking {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        val data = ByteArray(1300) { (it % 251).toByte() }
        val progress = ArrayList<Pair<Int, Int>>()
        client.write("/ext/test.bin", data) { sent, total -> progress.add(sent to total) }
        assertEquals(3, flipper.log.count { it == "write /ext/test.bin" }, "512 + 512 + 276 bytes")
        assertTrue(flipper.files["/ext/test.bin"].contentEquals(data))
        assertEquals(1300, progress.last().first)
        val back = client.read("/ext/test.bin")
        assertTrue(back.contentEquals(data))
    }

    @Test
    fun emptyFileIsWrittenAsOneRequest(): Unit = runBlocking {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        client.write("/ext/empty.txt", ByteArray(0))
        assertTrue(flipper.files["/ext/empty.txt"].contentEquals(ByteArray(0)))
    }

    @Test
    fun readRefusesDirectoriesAndLargeFiles(): Unit = runBlocking {
        val flipper = SimulatedFlipper(mapOf("/ext/big.bin" to "x".repeat(2000)))
        val client = flipper.client()
        assertThrowsSuspend<FlipperException.InvalidPath> { client.read("/ext", maxBytes = 100) }
        assertThrowsSuspend<FlipperException.Rpc> { client.read("/ext/big.bin", maxBytes = 100) }
        assertFalse(flipper.log.contains("read /ext/big.bin"), "size is checked before any transfer")
    }

    @Test
    fun makeDirectoriesCreatesMissingParentsAndToleratesExisting(): Unit = runBlocking {
        val flipper = SimulatedFlipper(mapOf("/ext/update/old.txt" to "x"))
        val client = flipper.client()
        client.makeDirectories("/ext/update/f7-update-mntm-012")
        assertTrue(flipper.dirs.contains("/ext/update/f7-update-mntm-012"))
        assertEquals(listOf("mkdir /ext/update", "mkdir /ext/update/f7-update-mntm-012"), flipper.log)
    }

    @Test
    fun deleteRenameAndInfo(): Unit = runBlocking {
        val flipper = SimulatedFlipper(mapOf("/ext/a/one.txt" to "1", "/ext/a/two.txt" to "2", "/ext/b.txt" to "b"))
        val client = flipper.client()
        client.rename("/ext/b.txt", "/ext/c.txt")
        client.delete("/ext/a", recursive = true)
        val info = client.storageInfo()
        assertEquals(setOf("/ext/c.txt"), flipper.files.keys)
        assertEquals(FlipperStorageInfo(totalSpace = 1000, freeSpace = 400), info)
        assertTrue(flipper.log.contains("delete /ext/a recursive"))
    }

    @Test
    fun pathsOutsideTheSdCardNeverLeaveThePhone(): Unit = runBlocking {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        for (path in listOf("/ext/../int/secret", "relative.txt", "/etc/passwd")) {
            assertThrowsSuspend<FlipperException.InvalidPath> { client.read(path) }
        }
        assertTrue(flipper.log.isEmpty())
    }

    // MARK: Device name

    @Test
    fun deviceNameIsWrittenAndReadBack(): Unit = runBlocking {
        val flipper = SimulatedFlipper()
        val client = flipper.client()
        assertNull(client.customDeviceName())
        client.setDeviceName("Hero")
        assertEquals("Hero", client.customDeviceName())
        assertEquals(
            "Filetype: Flipper Name File\nVersion: 1\nName: Hero\n",
            String(flipper.files[FlipperName.PATH]!!, Charsets.UTF_8),
        )
    }

    @Test
    fun deviceNameValidation(): Unit = runBlocking {
        assertEquals("Hero_1", FlipperName.validate("  Hero_1 "))
        assertEquals(FlipperName.NameError.TooLong(9), assertThrowsSuspend<FlipperName.NameError> { FlipperName.validate("NineChars") })
        assertEquals(FlipperName.NameError.Empty, assertThrowsSuspend<FlipperName.NameError> { FlipperName.validate("") })
        assertEquals(FlipperName.NameError.InvalidCharacters, assertThrowsSuspend<FlipperName.NameError> { FlipperName.validate("a b") })
        assertNull(FlipperName.parse("Filetype: Flipper Name File\nName: \n"))

        val flipper = SimulatedFlipper()
        val client = flipper.client()
        assertThrowsSuspend<FlipperName.NameError> { client.setDeviceName("WayTooLongName") }
        assertNull(flipper.files[FlipperName.PATH], "an invalid name never reaches the SD card")
    }
}
