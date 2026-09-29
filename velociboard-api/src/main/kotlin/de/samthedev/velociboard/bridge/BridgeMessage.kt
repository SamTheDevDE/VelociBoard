package de.samthedev.velociboard.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

class BridgeMessage(val playerId: UUID, values: Map<String, String>) {
    val values = values.toMap()

    init {
        require(playerId != UUID(0, 0) && this.values.size <= MAX_VALUES) { "Invalid player UUID or value count" }
        this.values.forEach(::validate)
    }

    override fun equals(other: Any?): Boolean = other is BridgeMessage && playerId == other.playerId && values == other.values
    override fun hashCode(): Int = 31 * playerId.hashCode() + values.hashCode()

    fun playerId(): UUID = playerId
    fun values(): Map<String, String> = values

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(VERSION)
            output.writeByte(VALUES_TYPE)
            output.writeLong(playerId.mostSignificantBits)
            output.writeLong(playerId.leastSignificantBits)
            output.writeByte(values.size)
            for ((key, value) in values) {
                val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
                val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
                output.writeByte(keyBytes.size)
                output.write(keyBytes)
                output.writeShort(valueBytes.size)
                output.write(valueBytes)
            }
        }
        require(bytes.size() <= MAX_BYTES) { "Bridge message exceeds $MAX_BYTES bytes" }
        return bytes.toByteArray()
    }

    companion object {
        const val CHANNEL = "velociboard:bridge"
        const val MAX_BYTES = 4096
        private const val VERSION = 1
        private const val VALUES_TYPE = 1
        private const val MAX_VALUES = 32
        private const val MAX_VALUE_BYTES = 256

        @JvmStatic
        fun decode(bytes: ByteArray?): BridgeMessage {
            require(bytes != null && bytes.size in 19..MAX_BYTES) { "Invalid bridge message length" }
            try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    require(input.readUnsignedByte() == VERSION && input.readUnsignedByte() == VALUES_TYPE) {
                        "Unknown bridge version or message type"
                    }
                    val playerId = UUID(input.readLong(), input.readLong())
                    val count = input.readUnsignedByte()
                    require(count <= MAX_VALUES) { "Too many bridge values" }
                    val values = linkedMapOf<String, String>()
                    repeat(count) {
                        val keyLength = input.readUnsignedByte()
                        require(keyLength in 1..32) { "Invalid bridge key length" }
                        val key = readUtf8(input, keyLength)
                        val valueLength = input.readUnsignedShort()
                        require(valueLength <= MAX_VALUE_BYTES) { "Bridge value is too long" }
                        val value = readUtf8(input, valueLength)
                        require(values.putIfAbsent(key, value) == null) { "Duplicate bridge key" }
                    }
                    require(input.available() == 0) { "Trailing bridge data" }
                    return BridgeMessage(playerId, values.toMap())
                }
            } catch (error: java.io.IOException) {
                throw IllegalArgumentException("Truncated bridge message", error)
            }
        }

        private fun readUtf8(input: DataInputStream, length: Int): String {
            val bytes = ByteArray(length)
            input.readFully(bytes)
            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (error: CharacterCodingException) {
                throw IllegalArgumentException("Invalid UTF-8 in bridge message", error)
            }
        }

        private fun validate(key: String, value: String) {
            require(key.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "Invalid bridge key" }
            require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_VALUE_BYTES && value.none(Char::isISOControl)) {
                "Invalid bridge value for $key"
            }
        }
    }
}
