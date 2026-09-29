package de.samthedev.velociboard.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record BridgeMessage(UUID playerId, Map<String, String> values) {
    public static final String CHANNEL = "velociboard:bridge";
    public static final int MAX_BYTES = 4096;
    private static final int VERSION = 1;
    private static final int VALUES_TYPE = 1;
    private static final int MAX_VALUES = 32;
    private static final int MAX_VALUE_BYTES = 256;

    public BridgeMessage {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(values);
        if (playerId.equals(new UUID(0, 0)) || values.size() > MAX_VALUES) {
            throw new IllegalArgumentException("Invalid player UUID or value count");
        }
        values.forEach(BridgeMessage::validate);
        values = Map.copyOf(values);
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(VERSION);
            output.writeByte(VALUES_TYPE);
            output.writeLong(playerId.getMostSignificantBits());
            output.writeLong(playerId.getLeastSignificantBits());
            output.writeByte(values.size());
            for (var entry : values.entrySet()) {
                byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
                output.writeByte(key.length);
                output.write(key);
                output.writeShort(value.length);
                output.write(value);
            }
            if (bytes.size() > MAX_BYTES) {
                throw new IllegalArgumentException("Bridge message exceeds " + MAX_BYTES + " bytes");
            }
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public static BridgeMessage decode(byte[] bytes) {
        if (bytes == null || bytes.length < 19 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Invalid bridge message length");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readUnsignedByte() != VERSION || input.readUnsignedByte() != VALUES_TYPE) {
                throw new IllegalArgumentException("Unknown bridge version or message type");
            }
            UUID playerId = new UUID(input.readLong(), input.readLong());
            int count = input.readUnsignedByte();
            if (count > MAX_VALUES) {
                throw new IllegalArgumentException("Too many bridge values");
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                int keyLength = input.readUnsignedByte();
                if (keyLength < 1 || keyLength > 32) {
                    throw new IllegalArgumentException("Invalid bridge key length");
                }
                String key = readUtf8(input, keyLength);
                int valueLength = input.readUnsignedShort();
                if (valueLength > MAX_VALUE_BYTES) {
                    throw new IllegalArgumentException("Bridge value is too long");
                }
                String value = readUtf8(input, valueLength);
                if (values.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("Duplicate bridge key");
                }
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("Trailing bridge data");
            }
            return new BridgeMessage(playerId, values);
        } catch (IOException error) {
            throw new IllegalArgumentException("Truncated bridge message", error);
        }
    }

    private static String readUtf8(DataInputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid UTF-8 in bridge message", error);
        }
    }

    private static void validate(String key, String value) {
        if (key == null || !key.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("Invalid bridge key");
        }
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid bridge value for " + key);
        }
    }
}
