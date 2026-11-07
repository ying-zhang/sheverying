package moe.shizuku.manager.adb

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AdbMessageTest {
    @Test fun validPacketRoundTrip() {
        val message = AdbMessage(AdbProtocol.A_WRTE, 1, 2, byteArrayOf(1, 2, -1))
        assertEquals(message, read(message.toByteArray()))
    }

    @Test fun rejectsOversizedAndNegativeLengthsBeforeReadingPayload() {
        for (length in listOf(-1, AdbProtocol.A_MAXDATA + 1, Int.MAX_VALUE)) {
            val error = assertThrows(IOException::class.java) { read(header(length)) }
            assertFalse("Must reject header before trying to read payload", error is EOFException)
        }
    }

    @Test fun rejectsNonAdbServiceBeforeReadingPayload() {
        val packet = header(128).also { it[20] = (it[20].toInt() xor 1).toByte() }
        val error = assertThrows(IOException::class.java) { read(packet) }
        assertFalse(error is EOFException)
    }

    @Test fun truncatedPacketFailsWithoutWaitingForAnotherMessage() {
        assertThrows(EOFException::class.java) { read(header(4)) }
    }

    private fun header(length: Int): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(AdbProtocol.A_WRTE).putInt(1).putInt(2).putInt(length).putInt(0)
        .putInt(AdbProtocol.A_WRTE xor -1).array()

    private fun read(bytes: ByteArray) = AdbMessage.readFrom(DataInputStream(ByteArrayInputStream(bytes)))
}
