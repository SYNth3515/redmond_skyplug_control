package com.example.rsp

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID

val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** type: 0 once, 1 daily, 2 weekly, 3 monthly; action: 3 on, 4 off; rules: weekday bits in UTC (bit0 = Monday) */
data class Ev(val uid: Int, val epoch: Long, val enabled: Boolean, val type: Int, val rules: Int, val action: Int)

@SuppressLint("MissingPermission")
class Rsp(private val ctx: Context, private val token: ByteArray) {
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private val waiters = HashMap<Int, CompletableDeferred<ByteArray>>()
    private val buf = ArrayList<Byte>()
    private var pid = 0
    @Volatile var lastState: ByteArray? = null
    private val ready = CompletableDeferred<Boolean>()

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else ready.complete(false)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val s = g.getService(SERVICE)
            val tx = s?.getCharacteristic(TX)
            rx = s?.getCharacteristic(RX)
            val d = tx?.getDescriptor(CCCD)
            if (tx == null || rx == null || d == null) { ready.complete(false); return }
            g.setCharacteristicNotification(tx, true)
            @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE }
            @Suppress("DEPRECATION") g.writeDescriptor(d)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { feed(c.value) }
    }

    private fun feed(data: ByteArray) {
        synchronized(buf) {
            data.forEach { buf.add(it) }
            while (true) {
                val s = buf.indexOf(0x55.toByte())
                if (s < 0) { buf.clear(); return }
                var e = -1
                for (i in s + 3 until buf.size) if (buf[i] == 0xAA.toByte()) { e = i; break }
                if (e < 0) return
                val frame = ByteArray(e - s + 1) { buf[s + it] }
                repeat(e + 1) { buf.removeAt(0) }
                if (frame[2].toInt() == 6) lastState = frame.copyOfRange(3, frame.size - 1)
                val key = ((frame[1].toInt() and 255) shl 8) or (frame[2].toInt() and 255)
                synchronized(waiters) { waiters.remove(key) }?.complete(frame)
            }
        }
    }

    /** Connects, enables notifications and authenticates with the token. */
    suspend fun open(address: String): Boolean {
        val dev = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(address)
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (withTimeoutOrNull(15000) { ready.await() } != true) return false
        val r = call(0xFF, token, required = false)
        return r != null && r.isNotEmpty() && r[0].toInt() != 0   // any non-zero = accepted
    }

    fun close() { try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {} }

    suspend fun call(cmd: Int, data: ByteArray = ByteArray(0), timeoutMs: Long = 4000, required: Boolean = true): ByteArray? {
        val id = pid; pid = if (pid >= 100) 0 else pid + 1
        val key = (id shl 8) or cmd
        val d = CompletableDeferred<ByteArray>()
        synchronized(waiters) { waiters[key] = d }
        val pkt = byteArrayOf(0x55, id.toByte(), cmd.toByte()) + data + byteArrayOf(0xAA.toByte())
        val ch = rx!!
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION") run { ch.value = pkt }
        @Suppress("DEPRECATION") gatt!!.writeCharacteristic(ch)
        val f = withTimeoutOrNull(timeoutMs) { d.await() }
        if (f == null) {
            synchronized(waiters) { waiters.remove(key) }
            if (required) throw IllegalStateException("нет ответа на команду 0x%02x".format(cmd))
            return null
        }
        return f.copyOfRange(3, f.size - 1)
    }
}

private fun le(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()

fun parseEv(p: ByteArray): Ev {
    val ep = ByteBuffer.wrap(p, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    return Ev(p[4].toInt() and 255, ep, (p[5].toInt() and 1) == 1, (p[5].toInt() and 255) shr 1, p[6].toInt() and 255, p[9].toInt() and 255)
}

fun buildEv(epoch: Long, type: Int, action: Int, rules: Int): ByteArray =
    le(epoch.toInt()) + byteArrayOf(0, (1 or (type shl 1)).toByte(), rules.toByte(), 0, 0, action.toByte(), 1, 0, 0, 0, 0, 0)

suspend fun Rsp.txOpen(uid: Int) {
    var r = call(0x76, byteArrayOf(uid.toByte(), 0, 0, 0))!!
    if (r[0].toInt() == 15) { call(0x77, ByteArray(4)); r = call(0x76, byteArrayOf(uid.toByte(), 0, 0, 0))!! }
    check(r[0].toInt() == 0) { "транзакция календаря: ошибка ${r[0]}" }
}
suspend fun Rsp.txClose() { call(0x77, ByteArray(4)) }

suspend fun Rsp.readCalendar(): Pair<String, List<Ev>> {
    val i = call(0x73)!!
    val ver = i[0].toInt() and 255; val max = i[1].toInt() and 255; val cur = i[2].toInt() and 255
    val bb = ByteBuffer.wrap(call(0x6F)!!).order(ByteOrder.LITTLE_ENDIAN)
    val dev = bb.int.toLong() and 0xFFFFFFFFL; val shift = bb.int
    val evs = ArrayList<Ev>()
    if (cur > 0) {
        txOpen(0)
        try {
            for (uid in 0..max) {
                val p = call(0x70, byteArrayOf(uid.toByte()))!!
                if (p.size >= 15 && (p.copyOfRange(0, 4).any { it.toInt() != 0 } || p[5].toInt() != 0 || p[9].toInt() != 0)) {
                    evs += parseEv(p); if (evs.size >= cur) break
                }
            }
        } finally { txClose() }
    }
    val diff = dev - Instant.now().epochSecond
    return "Календарь v$ver, ёмкость $max, занято $cur. Часы розетки отличаются от телефона на $diff с, пояс в розетке ${shift / 3600.0} ч" to evs
}

suspend fun Rsp.addEvent(epoch: Long, type: Int, action: Int, rules: Int): String {
    txOpen(1)
    val r = try { call(0x71, buildEv(epoch, type, action, rules))!! } finally { txClose() }
    check(r[1].toInt() == 0) { "добавление: ошибка ${r[1]}" }
    return "добавлено, uid=${r[0]}"
}
suspend fun Rsp.removeEvent(uid: Int) {
    txOpen(1)
    val r = try { call(0x74, byteArrayOf(uid.toByte()))!! } finally { txClose() }
    check(r[0].toInt() == 0) { "удаление: ошибка ${r[0]}" }
}
suspend fun Rsp.clearAll() {
    txOpen(1)
    val r = try { call(0x72)!! } finally { txClose() }
    check(r[0].toInt() == 0) { "очистка: ошибка ${r[0]}" }
}
suspend fun Rsp.syncTime(offsetSec: Int) {
    val r = call(0x6E, le(Instant.now().epochSecond.toInt()) + le(offsetSec))!!
    check(r[0].toInt() == 0) { "установка времени: ошибка ${r[0]}" }
}
suspend fun Rsp.power(on: Boolean): ByteArray = call(if (on) 0x03 else 0x04)!!
suspend fun Rsp.lock(on: Boolean): ByteArray = call(0x16, byteArrayOf(if (on) 1 else 0))!!
suspend fun Rsp.status(): ByteArray? { val r = call(0x06, required = false); if (r != null) return r; delay(1500); return lastState }
