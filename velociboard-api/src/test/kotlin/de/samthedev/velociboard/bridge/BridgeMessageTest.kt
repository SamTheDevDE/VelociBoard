package de.samthedev.velociboard.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class BridgeMessageTest {
    @Test
    fun roundTripsValues() {
        val message = BridgeMessage(UUID.randomUUID(), mapOf("world" to "overworld", "x" to "12"))
        assertEquals(message, BridgeMessage.decode(message.encode()))
    }

    @Test
    fun rejectsUnknownTypesAndTruncatedMessages() {
        val data = BridgeMessage(UUID.randomUUID(), mapOf("world" to "overworld")).encode()
        data[1] = 9
        assertThrows(IllegalArgumentException::class.java) { BridgeMessage.decode(data) }
        assertThrows(IllegalArgumentException::class.java) { BridgeMessage.decode(ByteArray(4)) }
    }

    @Test
    fun rejectsTrailingDataAndZeroUuid() {
        val data = BridgeMessage(UUID.randomUUID(), emptyMap()).encode()
        assertThrows(IllegalArgumentException::class.java) { BridgeMessage.decode(data.copyOf(data.size + 1)) }
        val zeroUuid = ByteArray(19)
        zeroUuid[0] = 1
        zeroUuid[1] = 1
        assertThrows(IllegalArgumentException::class.java) { BridgeMessage.decode(zeroUuid) }
    }

    @Test
    fun rejectsInvalidKeysAndOversizedValues() {
        val id = UUID.randomUUID()
        assertThrows(IllegalArgumentException::class.java) { BridgeMessage(id, mapOf("../path" to "x")) }
        assertThrows(IllegalArgumentException::class.java) { BridgeMessage(id, mapOf("world" to "x".repeat(257))) }
    }
}
