package moe.shizuku.manager.adb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbPairClient"
private const val CONNECT_TIMEOUT_MS = 5_000
private const val IO_TIMEOUT_MS = 10_000
private const val PAIR_TIMEOUT_MS = 15_000L

private const val kCurrentKeyHeaderVersion = 1.toByte()
private const val kMinSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxPeerInfoSize = 8192
private const val kMaxPayloadSize = kMaxPeerInfoSize * 2

private const val kExportedKeyLabel = "adb-label\u0000"
private const val kExportedKeySize = 64

private const val kPairingPacketHeaderSize = 6

private class PeerInfo(
        val type: Byte,
        data: ByteArray) {

    val data = ByteArray(kMaxPeerInfoSize - 1)

    init {
        data.copyInto(this.data, 0, 0, data.size.coerceAtMost(kMaxPeerInfoSize - 1))
    }

    enum class Type(val value: Byte) {
        ADB_RSA_PUB_KEY(0.toByte()),
        ADB_DEVICE_GUID(0.toByte()),
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(type)
            put(data)
        }

        Log.d(TAG, "write PeerInfo ${toStringShort()}")
    }

    override fun toString(): String {
        return "PeerInfo(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "type=$type, data=${data.contentToString()}"
    }

    companion object {

        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()
            val data = ByteArray(kMaxPeerInfoSize - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

private class PairingPacketHeader(
        val version: Byte,
        val type: Byte,
        val payload: Int) {

    enum class Type(val value: Byte) {
        SPAKE2_MSG(0.toByte()),
        PEER_INFO(1.toByte())
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(version)
            put(type)
            putInt(payload)
        }

        Log.d(TAG, "write PairingPacketHeader ${toStringShort()}")
    }

    override fun toString(): String {
        return "PairingPacketHeader(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "version=${version.toInt()}, type=${type.toInt()}, payload=$payload"
    }

    companion object {

        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int

            if (version < kMinSupportedKeyHeaderVersion || version > kMaxSupportedKeyHeaderVersion) {
                Log.e(TAG, "PairingPacketHeader version mismatch (us=$kCurrentKeyHeaderVersion them=${version})")
                return null
            }
            if (type != Type.SPAKE2_MSG.value && type != Type.PEER_INFO.value) {
                Log.e(TAG, "Unknown PairingPacket type=${type}")
                return null
            }
            if (payload <= 0 || payload > kMaxPayloadSize) {
                Log.e(TAG, "header payload not within a safe payload size (size=${payload})")
                return null
            }

            val header = PairingPacketHeader(version, type, payload)
            Log.d(TAG, "read PairingPacketHeader ${header.toStringShort()}")
            return header
        }
    }
}

private class PairingContext private constructor(private val nativePtr: Long) {

    val msg: ByteArray

    init {
        msg = nativeMsg(nativePtr)
    }

    internal fun initCipher(theirMsg: ByteArray) = nativeInitCipher(nativePtr, theirMsg)

    fun encrypt(`in`: ByteArray) = nativeEncrypt(nativePtr, `in`)

    fun decrypt(`in`: ByteArray) = nativeDecrypt(nativePtr, `in`)

    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray

    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean

    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDestroy(nativePtr: Long)

    companion object {

        fun create(password: ByteArray): PairingContext? {
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(private val host: String, private val port: Int, private val pairCode: String, private val key: AdbKey) : Closeable {

    private enum class State {
        Ready,
        ExchangingMsgs,
        ExchangingPeerInfo,
        Stopped
    }

    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null

    private val peerInfo: PeerInfo = PeerInfo(PeerInfo.Type.ADB_RSA_PUB_KEY.value, key.adbPublicKey)
    private var pairingContext: PairingContext? = null
    private var state: State = State.Ready
    private var closing = false

    fun start(): Boolean {
        // Closing the socket is what unblocks a read. soTimeout covers the
        // usual stall; the watchdog covers a handshake that ignores it.
        val watchdog = Thread({
            try {
                Thread.sleep(PAIR_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            closeSocket()
        }, "adb-pair-timeout")
        watchdog.isDaemon = true
        watchdog.start()
        try {
            setupTlsConnection()

            state = State.ExchangingMsgs

            if (!doExchangeMsgs()) {
                state = State.Stopped
                return false
            }

            state = State.ExchangingPeerInfo

            if (!doExchangePeerInfo()) {
                state = State.Stopped
                return false
            }

            state = State.Stopped
            return true
        } finally {
            watchdog.interrupt()
            close()
        }
    }

    private fun setupTlsConnection() {
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.soTimeout = IO_TIMEOUT_MS
        try {
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            adoptSocket(plain)

            val sslContext = key.sslContext
            val sslSocket = sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            sslSocket.soTimeout = IO_TIMEOUT_MS
            sslSocket.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            val input = DataInputStream(sslSocket.inputStream)
            val output = DataOutputStream(sslSocket.outputStream)
            synchronized(this) {
                if (closing) {
                    runCatching { input.close() }
                    runCatching { output.close() }
                    runCatching { sslSocket.close() }
                    throw IOException("pairing timed out")
                }
                inputStream = input
                outputStream = output
            }

            val pairCodeBytes = pairCode.toByteArray()
            val keyMaterial = Conscrypt.exportKeyingMaterial(sslSocket, kExportedKeyLabel, null, kExportedKeySize)
            val passwordBytes = ByteArray(pairCode.length + keyMaterial.size)
            pairCodeBytes.copyInto(passwordBytes)
            keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

            val created = PairingContext.create(passwordBytes)
            checkNotNull(created) { "Unable to create PairingContext." }
            synchronized(this) {
                if (closing) {
                    created.destroy()
                    throw IOException("pairing timed out")
                }
                pairingContext = created
            }
        } catch (t: Throwable) {
            runCatching { plain.close() }
            throw t
        }
    }

    @Synchronized
    private fun adoptSocket(plain: Socket) {
        if (closing) {
            plain.close()
            throw IOException("pairing timed out")
        }
        socket = plain
    }

    private fun input(): DataInputStream = inputStream ?: throw IOException("pairing closed")

    private fun output(): DataOutputStream = outputStream ?: throw IOException("pairing closed")

    private fun context(): PairingContext = pairingContext ?: throw IOException("pairing closed")

    private fun createHeader(type: PairingPacketHeader.Type, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(kCurrentKeyHeaderVersion, type.value, payloadSize)
    }

    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(kPairingPacketHeaderSize)
        input().readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return PairingPacketHeader.readFrom(buffer)
    }

    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)

        output().write(buffer.array())
        output().write(payload)
        Log.d(TAG, "write payload, size=${payload.size}")
    }

    private fun doExchangeMsgs(): Boolean {
        val msg = context().msg
        val size = msg.size

        val ourHeader = createHeader(PairingPacketHeader.Type.SPAKE2_MSG, size)
        writeHeader(ourHeader, msg)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG.value) return false

        val theirMessage = ByteArray(theirHeader.payload)
        input().readFully(theirMessage)

        if (!context().initCipher(theirMessage)) return false
        return true
    }

    private fun doExchangePeerInfo(): Boolean {
        val buf = ByteBuffer.allocate(kMaxPeerInfoSize).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buf)

        val outbuf = context().encrypt(buf.array()) ?: return false

        val ourHeader = createHeader(PairingPacketHeader.Type.PEER_INFO, outbuf.size)
        writeHeader(ourHeader, outbuf)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO.value) return false

        val theirMessage = ByteArray(theirHeader.payload)
        input().readFully(theirMessage)

        val decrypted = context().decrypt(theirMessage) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != kMaxPeerInfoSize) {
            Log.e(TAG, "Got size=${decrypted.size} PeerInfo.size=$kMaxPeerInfoSize")
            return false
        }
        val theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decrypted))
        Log.d(TAG, theirPeerInfo.toString())
        return true
    }

    /** Unblocks a stalled connect, handshake, or read without freeing native state. */
    @Synchronized
    private fun closeSocket() {
        closing = true
        inputStream?.let { runCatching { it.close() } }
        outputStream?.let { runCatching { it.close() } }
        socket?.let { runCatching { it.close() } }
    }

    @Synchronized
    override fun close() {
        closeSocket()
        inputStream = null
        outputStream = null
        socket = null
        pairingContext?.let { runCatching { it.destroy() } }
        pairingContext = null
        state = State.Stopped
    }

    companion object {

        init {
            System.loadLibrary("adb")
        }

        @JvmStatic
        external fun available(): Boolean
    }
}
