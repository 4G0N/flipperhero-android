package com.flipperhero.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.flipperhero.app.BuildConfig
import com.flipperhero.flipper.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one thing `:flipper` cannot do purely: a real BLE [Transport] over the Android GATT stack.
 *
 * Handles the Android realities the iOS implicit-pairing model does not translate to:
 *  - Explicit bond lifecycle (BOND_NONE → BOND_BONDING → BOND_BONDED). Subscribing to the
 *    authenticated read + flow-control characteristics triggers bonding; the triggering GATT op is
 *    retried after BOND_BONDED (see [enableNotifyWithBond]/[ensureBonded]).
 *  - MTU negotiation; the write chunk size is the negotiated MTU minus the 3-byte ATT header.
 *  - Credit-based (OVERFLOW) flow control on the write path (see [FlowControl]/[CreditGate]).
 *  - GATT callbacks arrive on a binder thread; all GATT operations are serialized under [gattLock]
 *    and connection-state races fail pending operations instead of hanging.
 *  - The RPC status characteristic ([FlipperGatt.FORBIDDEN_STATUS]) is never discovered, subscribed
 *    or written — a 0 there drops the link.
 *
 * Validated against real hardware at the Step 2(a) checkpoint; bonding is interactive.
 */
@SuppressLint("MissingPermission")
class AndroidBleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Transport {

    private val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()

    private val creditGate = CreditGate()
    private val gattLock = Mutex()

    @Volatile private var mtu = 23
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var serialWrite: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    @Volatile private var closed = false

    // One operation outstanding at a time (serialized by gattLock), so single deferreds are safe.
    private var connectionResult: CompletableDeferred<Unit>? = null
    private var servicesResult: CompletableDeferred<Int>? = null
    private var mtuResult: CompletableDeferred<Int>? = null
    private var descWriteResult: CompletableDeferred<Int>? = null
    private var charWriteResult: CompletableDeferred<Int>? = null
    private var charReadResult: CompletableDeferred<Pair<Int, ByteArray>>? = null

    private val bondChannel = Channel<Int>(Channel.CONFLATED)
    private var bondReceiver: BroadcastReceiver? = null

    private fun log(message: String) { if (BuildConfig.DEBUG) Log.d(TAG, message) }

    // ---- Public connect handshake -------------------------------------------------------------

    /** Runs the full connect/bond/MTU/subscribe handshake and returns this transport, ready. */
    suspend fun connect(timeoutMs: Long = 60_000): Transport {
        val result = withTimeoutOrNull(timeoutMs) {
            log("connect() start addr=${device.address} bondState=${device.bondState}")
            registerBondReceiver()
            connectGattWithRetry()
            negotiateMtu()
            log("MTU negotiated = $mtu (chunk = ${mtu - 3})")
            discoverServices()
            bindCharacteristics()
            log("characteristics bound; enabling notifications")
            // Subscribing to the authenticated characteristics is what triggers bonding.
            enableNotifyWithBond(requireChar(FlipperGatt.FLOW_CONTROL))
            enableNotifyWithBond(requireChar(FlipperGatt.SERIAL_READ))
            primeFlowControl()
            ready = true
            log("connect() ready")
            this@AndroidBleTransport
        }
        if (result == null) {
            shutdown(BleError.Timeout)
            throw BleError.Timeout
        }
        return result
    }

    private suspend fun connectGattWithRetry() {
        var lastError: Throwable = BleError.Disconnected
        repeat(3) { attempt ->
            val deferred = CompletableDeferred<Unit>()
            connectionResult = deferred
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            try {
                deferred.await()
                return
            } catch (e: Throwable) {
                lastError = e
                runCatching { gatt?.close() }
                gatt = null
                // GATT 133 on first connect is common and transient; back off and retry.
                delay(500L * (attempt + 1))
            }
        }
        throw lastError
    }

    private suspend fun negotiateMtu() = gattLock.withLock {
        val g = gatt ?: throw BleError.Disconnected
        val deferred = CompletableDeferred<Int>()
        mtuResult = deferred
        // 517 = max ATT payload; the device replies with what it supports.
        if (!g.requestMtu(517)) {
            mtuResult = null
            return@withLock // keep the 23-byte default
        }
        mtu = withTimeoutOrNull(5_000) { deferred.await() } ?: 23
    }

    private suspend fun discoverServices() = gattLock.withLock {
        val g = gatt ?: throw BleError.Disconnected
        val deferred = CompletableDeferred<Int>()
        servicesResult = deferred
        if (!g.discoverServices()) throw BleError.Underlying("discoverServices refused")
        val status = deferred.await()
        if (status != BluetoothGatt.GATT_SUCCESS) throw BleError.Underlying("service discovery failed ($status)")
    }

    private fun bindCharacteristics() {
        val service = gatt?.getService(FlipperGatt.SERVICE)
        log("serial service present = ${service != null}; all services = ${gatt?.services?.map { it.uuid }}")
        if (service == null) throw BleError.ServiceMissing
        // Only the three allowed characteristics are looked up; the status characteristic is left alone.
        serialWrite = service.getCharacteristic(FlipperGatt.SERIAL_WRITE)
        val read = service.getCharacteristic(FlipperGatt.SERIAL_READ)
        val flow = service.getCharacteristic(FlipperGatt.FLOW_CONTROL)
        log("props: serialRead=${read?.properties} serialWrite=${serialWrite?.properties} flowControl=${flow?.properties} (NOTIFY=0x10 INDICATE=0x20 WRITE=0x08 WRITE_NR=0x04 READ=0x02)")
        if (read == null || serialWrite == null || flow == null) throw BleError.ServiceMissing
    }

    private fun requireChar(uuid: java.util.UUID): BluetoothGattCharacteristic =
        gatt?.getService(FlipperGatt.SERVICE)?.getCharacteristic(uuid) ?: throw BleError.ServiceMissing

    /** Enables notifications, bonding first if needed and retrying the CCCD write after BOND_BONDED. */
    private suspend fun enableNotifyWithBond(characteristic: BluetoothGattCharacteristic) {
        val g = gatt ?: throw BleError.Disconnected
        val setOk = g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(FlipperGatt.CCCD) ?: throw BleError.ServiceMissing
        // Match the characteristic: NOTIFY -> 0x0100, INDICATE -> 0x0200. iOS's setNotifyValue picks
        // this automatically; on Android we must, or the firmware sends nothing despite a clean write.
        val useIndication = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) &&
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
        val cccdValue = if (useIndication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        log("enableNotify ${characteristic.uuid} setNotify=$setOk props=${characteristic.properties} useIndication=$useIndication bondState=${device.bondState}")

        // The CCCD lives on an authenticated characteristic, so the write needs a bond. Establish it
        // up front when absent, then write; retry once more if the first write is rejected and a bond
        // has since completed (BOND_BONDED).
        if (device.bondState != BluetoothDevice.BOND_BONDED) ensureBonded()

        repeat(2) { attempt ->
            val status = writeDescriptor(cccd, cccdValue)
            log("enableNotify ${characteristic.uuid} CCCD write attempt=$attempt status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) return
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                ensureBonded()
            } else if (attempt == 1) {
                return@repeat
            }
        }
        throw BleError.PairingFailed
    }

    /** Drives the Android bond state machine to BOND_BONDED (interactive — the user confirms a PIN). */
    private suspend fun ensureBonded() {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        while (bondChannel.tryReceive().isSuccess) { /* drain stale */ }
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            if (!device.createBond()) throw BleError.PairingFailed
        }
        val deadline = 40_000L
        withTimeoutOrNull(deadline) {
            while (true) {
                when (bondChannel.receive()) {
                    BluetoothDevice.BOND_BONDED -> return@withTimeoutOrNull
                    BluetoothDevice.BOND_NONE -> throw BleError.PairingFailed
                    else -> { /* BOND_BONDING: keep waiting */ }
                }
            }
        } ?: throw BleError.PairingFailed
    }

    private suspend fun primeFlowControl() {
        // A read of the flow-control characteristic yields the first credit value => ready to send.
        val value = readCharacteristic(requireChar(FlipperGatt.FLOW_CONTROL))
        val credits = FlowControl.parseCredits(value)
        log("primeFlowControl: ${value.size} bytes -> $credits credits")
        creditGate.supply(credits)
    }

    // ---- Transport -----------------------------------------------------------------------------

    override suspend fun send(data: ByteArray) {
        log("send ${data.size} bytes")
        var offset = 0
        while (offset < data.size) {
            val n = creditGate.reserve(data.size - offset, mtu)
            log("send chunk offset=$offset n=$n")
            writeChunk(data.copyOfRange(offset, offset + n))
            offset += n
        }
        log("send complete (${data.size} bytes)")
    }

    override suspend fun close() {
        shutdown(null)
    }

    private suspend fun writeChunk(chunk: ByteArray) = gattLock.withLock {
        val g = gatt ?: throw BleError.Disconnected
        val characteristic = serialWrite ?: throw BleError.Disconnected
        val deferred = CompletableDeferred<Int>()
        charWriteResult = deferred
        val ok = issueCharacteristicWrite(g, characteristic, chunk)
        if (!ok) {
            charWriteResult = null
            throw BleError.Underlying("writeCharacteristic refused")
        }
        val status = deferred.await()
        if (status != BluetoothGatt.GATT_SUCCESS) throw BleError.Underlying("write failed ($status)")
    }

    private suspend fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): Int = gattLock.withLock {
        val g = gatt ?: throw BleError.Disconnected
        val deferred = CompletableDeferred<Int>()
        descWriteResult = deferred
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { descriptor.value = value; g.writeDescriptor(descriptor) }
        }
        if (!ok) {
            descWriteResult = null
            // Some stacks fail the call synchronously before a bond; report non-success to trigger retry.
            return@withLock BluetoothGatt.GATT_FAILURE
        }
        withTimeoutOrNull(10_000) { deferred.await() } ?: BluetoothGatt.GATT_FAILURE
    }

    private suspend fun readCharacteristic(characteristic: BluetoothGattCharacteristic): ByteArray = gattLock.withLock {
        val g = gatt ?: throw BleError.Disconnected
        val deferred = CompletableDeferred<Pair<Int, ByteArray>>()
        charReadResult = deferred
        if (!g.readCharacteristic(characteristic)) {
            charReadResult = null
            throw BleError.Underlying("readCharacteristic refused")
        }
        val (status, value) = deferred.await()
        if (status != BluetoothGatt.GATT_SUCCESS) throw BleError.Underlying("read failed ($status)")
        value
    }

    private fun issueCharacteristicWrite(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            g.writeCharacteristic(characteristic)
        }
    }

    // ---- GATT callback (binder thread) ---------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("onConnectionStateChange status=$status newState=$newState ready=$ready")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectionResult?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val error = if (status == 133) BleError.Underlying("133") else BleError.Disconnected
                if (!ready) {
                    connectionResult?.completeExceptionally(error)
                } else {
                    scope.launch { shutdown(error) }
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionResult?.completeExceptionally(BleError.Underlying("connect status $status"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuResult?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("onServicesDiscovered status=$status")
            servicesResult?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("onDescriptorWrite ${descriptor.characteristic?.uuid} status=$status")
            descWriteResult?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("onCharacteristicWrite ${characteristic.uuid} status=$status")
            if (characteristic.uuid == FlipperGatt.SERIAL_WRITE) charWriteResult?.complete(status)
        }

        // API 33+
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            charReadResult?.complete(status to value)
        }

        // < API 33
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            charReadResult?.complete(status to (characteristic.value ?: ByteArray(0)))
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            routeNotification(characteristic.uuid, value)
        }

        // < API 33
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            routeNotification(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }
    }

    private fun routeNotification(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            FlipperGatt.SERIAL_READ -> incomingChannel.trySend(value) // per-frame; not logged (flood)
            FlipperGatt.FLOW_CONTROL -> {
                val credits = if (value.size >= 4) FlowControl.parseCredits(value) else -1
                log("notify FLOW_CONTROL ${value.size} bytes -> credits=$credits")
                if (value.size >= 4) scope.launch { creditGate.supply(credits) }
            }
            else -> log("notify OTHER $uuid ${value.size} bytes")
        }
    }

    // ---- Bonding broadcast ---------------------------------------------------------------------

    private fun registerBondReceiver() {
        if (bondReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changed: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                if (changed?.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                bondChannel.trySend(state)
            }
        }
        bondReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // ---- Teardown ------------------------------------------------------------------------------

    private suspend fun shutdown(error: Throwable?) {
        if (closed) return
        closed = true
        val failure = error ?: BleError.Disconnected
        connectionResult?.completeExceptionally(failure)
        servicesResult?.completeExceptionally(failure)
        mtuResult?.completeExceptionally(failure)
        descWriteResult?.completeExceptionally(failure)
        charWriteResult?.completeExceptionally(failure)
        charReadResult?.completeExceptionally(failure)
        creditGate.close(failure)
        incomingChannel.close()
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        scope.cancel()
    }

    private companion object {
        const val TAG = "FlipperBLE"
    }
}
