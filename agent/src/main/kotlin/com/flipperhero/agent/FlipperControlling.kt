package com.flipperhero.agent

import com.flipperhero.flipper.FlipperApp
import com.flipperhero.flipper.FlipperDirEntry
import com.flipperhero.flipper.FlipperGpioPin
import com.flipperhero.flipper.FlipperKey
import com.flipperhero.flipper.FlipperRpcClient
import com.flipperhero.flipper.FlipperScreenFrame
import com.flipperhero.flipper.FlipperStorageInfo
import com.flipperhero.flipper.customDeviceName
import com.flipperhero.flipper.delete
import com.flipperhero.flipper.deviceInfo
import com.flipperhero.flipper.emulate
import com.flipperhero.flipper.exitApp
import com.flipperhero.flipper.gpioRead
import com.flipperhero.flipper.gpioSetMode
import com.flipperhero.flipper.gpioWrite
import com.flipperhero.flipper.list
import com.flipperhero.flipper.loadBadKeyboardScript
import com.flipperhero.flipper.makeDirectory
import com.flipperhero.flipper.playAlert
import com.flipperhero.flipper.powerInfo
import com.flipperhero.flipper.press
import com.flipperhero.flipper.rawRpc
import com.flipperhero.flipper.read
import com.flipperhero.flipper.rename
import com.flipperhero.flipper.setDeviceName
import com.flipperhero.flipper.startApp
import com.flipperhero.flipper.stat
import com.flipperhero.flipper.storageInfo
import com.flipperhero.flipper.transmitOnce
import com.flipperhero.flipper.write
import kotlin.time.Duration

/**
 * The slice of the Flipper API the agent may use. Lets tests run without hardware. Mirrors Swift
 * `FlipperControlling`. In `:app`, a [FlipperRpcClient] is wrapped via [asControlling].
 */
interface FlipperControlling {
    suspend fun deviceInfo(): Map<String, String>
    suspend fun powerInfo(): Map<String, String>
    suspend fun storageInfo(path: String): FlipperStorageInfo
    suspend fun list(path: String): List<FlipperDirEntry>
    suspend fun stat(path: String): FlipperDirEntry
    suspend fun read(path: String, maxBytes: Int): ByteArray
    suspend fun write(path: String, data: ByteArray)
    suspend fun makeDirectory(path: String)
    suspend fun delete(path: String, recursive: Boolean)
    suspend fun rename(from: String, to: String)
    suspend fun startApp(name: String, args: String)
    suspend fun transmitOnce(app: FlipperApp, path: String, button: String, hold: Duration)
    suspend fun emulate(app: FlipperApp, path: String)
    suspend fun loadBadKeyboardScript(path: String)
    suspend fun exitApp()
    suspend fun playAlert()
    suspend fun setDeviceName(name: String)
    suspend fun customDeviceName(): String?
    suspend fun captureScreen(timeout: Duration): FlipperScreenFrame
    suspend fun press(key: FlipperKey, long: Boolean)
    suspend fun gpioSetMode(pin: FlipperGpioPin, output: Boolean, pullUp: Boolean?)
    suspend fun gpioRead(pin: FlipperGpioPin): Boolean
    suspend fun gpioWrite(pin: FlipperGpioPin, level: Boolean)
    suspend fun rawRpc(jsonRequest: String): String
}

/** Adapts the pure-`:flipper` client (whose device API is extension functions) to [FlipperControlling]. */
fun FlipperRpcClient.asControlling(): FlipperControlling = object : FlipperControlling {
    override suspend fun deviceInfo() = this@asControlling.deviceInfo()
    override suspend fun powerInfo() = this@asControlling.powerInfo()
    override suspend fun storageInfo(path: String) = this@asControlling.storageInfo(path)
    override suspend fun list(path: String) = this@asControlling.list(path)
    override suspend fun stat(path: String) = this@asControlling.stat(path)
    override suspend fun read(path: String, maxBytes: Int) = this@asControlling.read(path, maxBytes)
    override suspend fun write(path: String, data: ByteArray) = this@asControlling.write(path, data)
    override suspend fun makeDirectory(path: String) = this@asControlling.makeDirectory(path)
    override suspend fun delete(path: String, recursive: Boolean) = this@asControlling.delete(path, recursive)
    override suspend fun rename(from: String, to: String) = this@asControlling.rename(from, to)
    override suspend fun startApp(name: String, args: String) = this@asControlling.startApp(name, args)
    override suspend fun transmitOnce(app: FlipperApp, path: String, button: String, hold: Duration) =
        this@asControlling.transmitOnce(app, path, button, hold)
    override suspend fun emulate(app: FlipperApp, path: String) = this@asControlling.emulate(app, path)
    override suspend fun loadBadKeyboardScript(path: String) = this@asControlling.loadBadKeyboardScript(path)
    override suspend fun exitApp() = this@asControlling.exitApp()
    override suspend fun playAlert() = this@asControlling.playAlert()
    override suspend fun setDeviceName(name: String) = this@asControlling.setDeviceName(name)
    override suspend fun customDeviceName() = this@asControlling.customDeviceName()
    override suspend fun captureScreen(timeout: Duration) = this@asControlling.captureScreen(timeout)
    override suspend fun press(key: FlipperKey, long: Boolean) = this@asControlling.press(key, long)
    override suspend fun gpioSetMode(pin: FlipperGpioPin, output: Boolean, pullUp: Boolean?) =
        this@asControlling.gpioSetMode(pin, output, pullUp)
    override suspend fun gpioRead(pin: FlipperGpioPin) = this@asControlling.gpioRead(pin)
    override suspend fun gpioWrite(pin: FlipperGpioPin, level: Boolean) = this@asControlling.gpioWrite(pin, level)
    override suspend fun rawRpc(jsonRequest: String) = this@asControlling.rawRpc(jsonRequest)
}
