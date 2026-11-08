package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanjaymu.schemaregistry.client.SchemaRegistryClient;
import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sanjaymu.schemaregistry.local.LocalSchemaRegistryServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The producer → registry → consumer flow: producers on v1 and v2 of a subject's JSON schema
 * write framed messages, and one consumer validates and deserializes both.
 */
class JsonSchemaSerdeTest {

    static final String ORDER_V1 = """
            {"$schema":"http://json-schema.org/draft-07/schema#","type":"object",
             "properties":{"id":{"type":"integer"},"customer":{"type":"string","minLength":1},
                           "amount":{"type":"number","minimum":0}},
             "required":["id","customer","amount"]}""";

    static final String ORDER_V2 = """
            {"$schema":"http://json-schema.org/draft-07/schema#","type":"object",
             "properties":{"id":{"type":"integer"},"customer":{"type":"string","minLength":1},
                           "amount":{"type":"number","minimum":0},"coupon":{"type":["string","null"]}},
             "required":["id","customer","amount"]}""";

    /** Producer A's model (v1). */
    public record Order(long id, String customer, double amount) {
    }

    /** Producer B's model (v2) and the consumer's current model. */
    public record OrderV2(long id, String customer, double amount, String coupon) {
    }

    private LocalSchemaRegistryServer server;
    private SchemaRegistryClient client;

    @BeforeEach
    void start() throws Exception {
        server = LocalSchemaRegistryServer.start(0);
        client = SchemaRegistryClient.builder().baseUrl(server.url()).maxRetries(0).build();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void producersOnDifferentVersionsShareOneConsumer() {
        JsonSchemaSerializer producerV1 = JsonSchemaSerializer.builder(client)
                .subject("orders-value").schema(ORDER_V1).autoRegister(true).build();
        byte[] m1 = producerV1.serialize(new Order(1, "acme", 25.5));

        JsonSchemaSerializer producerV2 = JsonSchemaSerializer.builder(client)
                .subject("orders-value").schema(ORDER_V2).autoRegister(true).build();
        byte[] m2 = producerV2.serialize(new OrderV2(2, "globex", 10, "SAVE10"));

        assertEquals(1, producerV1.schemaVersion());
        assertEquals(2, producerV2.schemaVersion());

        JsonSchemaDeserializer consumer = JsonSchemaDeserializer.builder(client).expectedSubject("orders-value").build();

        DeserializedMessage<OrderV2> d1 = consumer.deserializeWithMetadata(m1, OrderV2.class);
        assertEquals(1, d1.version());
        assertEquals(new OrderV2(1, "acme", 25.5, null), d1.value());

        DeserializedMessage<OrderV2> d2 = consumer.deserializeWithMetadata(m2, OrderV2.class);
        assertEquals(2, d2.version());
        assertEquals("orders-value", d2.subject());
        assertEquals(new OrderV2(2, "globex", 10, "SAVE10"), d2.value());

        // An older consumer model still reads v2 messages; the extra field is ignored.
        assertEquals(new Order(2, "globex", 10), consumer.deserialize(m2, Order.class));
    }

    @Test
    void producerRejectsInvalidPayloads() {
        JsonSchemaSerializer producer = JsonSchemaSerializer.builder(client)
                .subject("orders-value").schema(ORDER_V1).autoRegister(true).build();
        SerdeException e = assertThrows(SerdeException.class,
                () -> producer.serialize(Map.of("id", "x", "customer", "", "amount", -1)));
        assertTrue(e.isValidationFailure());
        assertEquals(3, e.validationErrors().size(), e.validationErrors().toString());
    }

    @Test
    void consumerRejectsPayloadsThatDoNotMatchTheirSchema() {
        int id = client.register("orders-value", RegisterSchemaRequest.of(ORDER_V1,
                com.sanjaymu.schemaregistry.client.model.SchemaType.JSON));
        // A misbehaving producer frames an invalid payload with a valid schema id.
        byte[] forged = WireFormat.encode(id, "{\"id\":1}".getBytes(StandardCharsets.UTF_8));

        JsonSchemaDeserializer consumer = JsonSchemaDeserializer.builder(client).build();
        SerdeException e = assertThrows(SerdeException.class, () -> consumer.deserialize(forged, JsonNode.class));
        assertTrue(e.isValidationFailure());

        JsonNode unchecked = JsonSchemaDeserializer.builder(client).validate(false).build()
                .deserialize(forged, JsonNode.class);
        assertEquals(1, unchecked.get("id").asInt());
    }

    @Test
    void consumerEnforcesExpectedSubject() {
        JsonSchemaSerializer other = JsonSchemaSerializer.builder(client)
                .subject("payments-value").schema("{\"type\":\"object\"}").autoRegister(true).build();
        byte[] message = other.serialize(Map.of("x", 1));

        JsonSchemaDeserializer consumer = JsonSchemaDeserializer.builder(client).expectedSubject("orders-value").build();
        SerdeException e = assertThrows(SerdeException.class, () -> consumer.deserialize(message, JsonNode.class));
        assertTrue(e.getMessage().contains("not registered under subject 'orders-value'"), e.getMessage());

        DeserializedMessage<JsonNode> any = JsonSchemaDeserializer.builder(client).build()
                .deserializeWithMetadata(message, JsonNode.class);
        assertNull(any.subject());
    }

    @Test
    void serializerWithoutAutoRegisterRequiresRegisteredSchema() {
        JsonSchemaSerializer producer = JsonSchemaSerializer.builder(client)
                .subject("orders-value").schema(ORDER_V1).build();
        assertThrows(RuntimeException.class, () -> producer.serialize(new Order(1, "a", 1)));

        client.register("orders-value", RegisterSchemaRequest.of(ORDER_V1,
                com.sanjaymu.schemaregistry.client.model.SchemaType.JSON));
        JsonSchemaSerializer latest = JsonSchemaSerializer.builder(client)
                .subject("orders-value").useLatestVersion().build();
        assertEquals(producer.serialize(new Order(1, "a", 1)).length, latest.serialize(new Order(1, "a", 1)).length);
    }

    @Test
    void wireFormat() {
        byte[] framed = WireFormat.encode(258, new byte[]{'{', '}'});
        assertArrayEquals(new byte[]{0, 0, 0, 1, 2, '{', '}'}, framed);
        assertEquals(258, WireFormat.decode(framed).schemaId());
        assertThrows(SerdeException.class, () -> WireFormat.decode(new byte[]{1, 0, 0, 0, 1}));
        assertThrows(SerdeException.class, () -> WireFormat.decode(new byte[]{0, 1}));
    }
}
