package com.sanjaymu.schemaregistry.serde;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The registry wire format shared by Confluent-compatible serializers:
 * <pre>
 * byte 0      magic byte (0)
 * bytes 1-4   schema id (big-endian int)
 * bytes 5..   payload (UTF-8 JSON)
 * </pre>
 * The schema id identifies the exact subject/version the producer used, so a consumer can fetch
 * that schema from the registry.
 */
public final class WireFormat {

    public static final byte MAGIC_BYTE = 0x0;
    public static final int HEADER_SIZE = 5;

    private WireFormat() {
    }

    /** A decoded message: the schema id from the header and the raw payload bytes. */
    public record Framed(int schemaId, byte[] payload) {
    }

    public static byte[] encode(int schemaId, byte[] payload) {
        return ByteBuffer.allocate(HEADER_SIZE + payload.length)
                .put(MAGIC_BYTE)
                .putInt(schemaId)
                .put(payload)
                .array();
    }

    public static Framed decode(byte[] message) {
        if (message == null || message.length < HEADER_SIZE) {
            throw new SerdeException("Message is too short to contain a schema registry header");
        }
        if (message[0] != MAGIC_BYTE) {
            throw new SerdeException("Unknown magic byte " + message[0] + "; payload was not written by a "
                    + "schema-registry-aware serializer");
        }
        int id = ByteBuffer.wrap(message, 1, 4).getInt();
        return new Framed(id, Arrays.copyOfRange(message, HEADER_SIZE, message.length));
    }
}
