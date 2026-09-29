package de.samthedev.velociboard.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BridgeMessageTest {
    @Test
    void roundTripsValues() {
        BridgeMessage message = new BridgeMessage(UUID.randomUUID(), Map.of("world", "overworld", "x", "12"));
        assertEquals(message, BridgeMessage.decode(message.encode()));
    }

    @Test
    void rejectsUnknownTypesAndTruncatedMessages() {
        byte[] data = new BridgeMessage(UUID.randomUUID(), Map.of("world", "overworld")).encode();
        data[1] = 9;
        assertThrows(IllegalArgumentException.class, () -> BridgeMessage.decode(data));
        assertThrows(IllegalArgumentException.class, () -> BridgeMessage.decode(new byte[4]));
    }

    @Test
    void rejectsTrailingDataAndZeroUuid() {
        byte[] data = new BridgeMessage(UUID.randomUUID(), Map.of()).encode();
        assertThrows(IllegalArgumentException.class, () -> BridgeMessage.decode(Arrays.copyOf(data, data.length + 1)));
        byte[] zeroUuid = new byte[19];
        zeroUuid[0] = 1;
        zeroUuid[1] = 1;
        assertThrows(IllegalArgumentException.class, () -> BridgeMessage.decode(zeroUuid));
    }

    @Test
    void rejectsInvalidKeysAndOversizedValues() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new BridgeMessage(id, Map.of("../path", "x")));
        assertThrows(IllegalArgumentException.class, () -> new BridgeMessage(id, Map.of("world", "x".repeat(257))));
    }
}
