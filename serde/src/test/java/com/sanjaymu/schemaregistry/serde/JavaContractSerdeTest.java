package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanjaymu.schemaregistry.client.SchemaRegistryClient;
import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sanjaymu.schemaregistry.client.model.SchemaType;
import com.sanjaymu.schemaregistry.local.LocalSchemaRegistryServer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The producer → registry → consumer flow with the schema enforced in Java: models carry the types, required
 * properties and constraints; there is no JSON Schema validation.
 */
class JavaContractSerdeTest {

    static final ObjectMapper MAPPER = SerdeMappers.defaultMapper();

    public record Buyer(@NotBlank @Email String email) {
    }

    /** Producer A's model (v1). */
    public record Order(long id, @NotBlank String customer, @NotNull @PositiveOrZero BigDecimal amount) {
    }

    /** Producer B's model (v2) and the consumer's current model. */
    public record OrderV2(long id, @NotBlank String customer, @NotNull @PositiveOrZero BigDecimal amount,
                          @Size(max = 20) String coupon, @Valid Buyer buyer) {
    }

    public record Payment(long id) {
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

    private JsonSchemaSerializer producer(Class<?> type) {
        return JsonSchemaSerializer.builder(client).subject("orders-value").forType(type).autoRegister(true).build();
    }

    private byte[] forged(int schemaId, String json) {
        return WireFormat.encode(schemaId, json.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> readErrors(JsonSchemaDeserializer consumer, byte[] message) {
        SerdeException e = assertThrows(SerdeException.class, () -> consumer.deserialize(message, OrderV2.class));
        assertTrue(e.isValidationFailure(), e.getMessage());
        return e.validationErrors();
    }

    @Test
    void constraintsAreExpressedInTheGeneratedSchema() throws Exception {
        JsonNode schema = MAPPER.readTree(JavaTypeSchemaGenerator.generate(OrderV2.class));
        assertEquals(List.of("amount", "customer", "id"), MAPPER.convertValue(schema.get("required"), List.class));
        assertEquals("string", schema.at("/properties/customer/type").asText(), "@NotBlank → required, not nullable");
        assertEquals(1, schema.at("/properties/customer/minLength").asInt());
        assertEquals(0, schema.at("/properties/amount/minimum").asInt());
        assertEquals(20, schema.at("/properties/coupon/maxLength").asInt());
        assertEquals("email", schema.at("/properties/buyer/properties/email/format").asText());
    }

    @Test
    void producersOnDifferentVersionsShareOneConsumer() {
        byte[] m1 = producer(Order.class).serialize(new Order(1, "acme", new BigDecimal("25.50")));
        JsonSchemaSerializer v2 = producer(OrderV2.class);
        byte[] m2 = v2.serialize(new OrderV2(2, "globex", BigDecimal.TEN, "SAVE10", new Buyer("ops@globex.example")));
        assertEquals(2, v2.schemaVersion());

        JsonSchemaDeserializer consumer = JsonSchemaDeserializer.builder(client)
                .expectedSubject("orders-value").targetType(OrderV2.class).build();

        DeserializedMessage<OrderV2> d1 = consumer.deserializeWithMetadata(m1, OrderV2.class);
        assertEquals(1, d1.version());
        assertEquals(new OrderV2(1, "acme", new BigDecimal("25.50"), null, null), d1.value());

        DeserializedMessage<OrderV2> d2 = consumer.deserializeWithMetadata(m2, OrderV2.class);
        assertEquals(2, d2.version());
        assertEquals("SAVE10", d2.value().coupon());

        // An older consumer model still reads v2 messages; the extra fields are ignored.
        assertEquals(new Order(2, "globex", BigDecimal.TEN), consumer.deserialize(m2, Order.class));
    }

    @Test
    void producerEnforcesConstraints() {
        JsonSchemaSerializer producer = producer(OrderV2.class);
        SerdeException e = assertThrows(SerdeException.class, () -> producer.serialize(
                new OrderV2(1, " ", new BigDecimal("-1"), "x".repeat(21), new Buyer("not-an-email"))));
        assertEquals(List.of(
                "$.amount: must be greater than or equal to 0",
                "$.buyer.email: must be a well-formed email address",
                "$.coupon: size must be between 0 and 20",
                "$.customer: must not be blank"), e.validationErrors());
    }

    @Test
    void producerRejectsUntypedValues() {
        JsonSchemaSerializer producer = producer(Order.class);
        assertThrows(IllegalArgumentException.class, () -> producer.serialize(Map.of("id", 1)));
        assertThrows(IllegalArgumentException.class, () -> producer.serialize(MAPPER.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> producer.serialize(new Payment(1)), "wrong class");
    }

    @Test
    void producerChecksItsClassAgainstAnExistingSchema() {
        client.register("orders-value", RegisterSchemaRequest.of(
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}", SchemaType.JSON));
        JsonSchemaSerializer latest = JsonSchemaSerializer.builder(client)
                .subject("orders-value").useLatestVersion().build();
        SchemaTypeMismatchException e = assertThrows(SchemaTypeMismatchException.class,
                () -> latest.serialize(new Order(1, "acme", BigDecimal.ONE)));
        assertTrue(e.report().errors().contains("$.id: schema type 'string' cannot be mapped to Java type long"),
                e.report().format());
    }

    @Test
    void consumerEnforcesTheJavaContract() {
        int id = producer(OrderV2.class).schemaId();
        JsonSchemaDeserializer consumer = JsonSchemaDeserializer.builder(client).build();

        assertEquals(List.of("$.customer: required property is missing"),
                readErrors(consumer, forged(id, "{\"id\":1,\"amount\":5}")));
        assertEquals(List.of("$.id: required property is missing"),
                readErrors(consumer, forged(id, "{\"customer\":\"a\",\"amount\":5}")), "missing primitive is not 0");
        assertEquals(List.of("$.amount: required property is null"),
                readErrors(consumer, forged(id, "{\"id\":1,\"customer\":\"a\",\"amount\":null}")));
        assertEquals(List.of("$.amount: must be greater than or equal to 0", "$.buyer.email: must not be blank"),
                readErrors(consumer, forged(id, "{\"id\":1,\"customer\":\"a\",\"amount\":-3,\"buyer\":{\"email\":\"\"}}")));
        assertEquals(List.of("$.buyer.email: required property is missing"),
                readErrors(consumer, forged(id, "{\"id\":1,\"customer\":\"a\",\"amount\":1,\"buyer\":{}}")));
    }

    @Test
    void consumerMapsTypesStrictly() {
        int id = producer(OrderV2.class).schemaId();
        JsonSchemaDeserializer consumer = JsonSchemaDeserializer.builder(client).build();
        String base = "\"customer\":\"a\",\"amount\":1";
        for (String bad : List.of(
                "{\"id\":\"1\"," + base + "}",                              // string → long
                "{\"id\":1.5," + base + "}",                                // float → long
                "{\"id\":1,\"customer\":5,\"amount\":1}",                   // number → String
                "{\"id\":1,\"customer\":\"a\",\"amount\":\"1\"}",           // string → BigDecimal
                "{\"id\":1," + base + ",\"coupon\":true}")) {               // boolean → String
            List<String> errors = readErrors(consumer, forged(id, bad));
            assertEquals(1, errors.size(), bad + " -> " + errors);
        }
    }

    @Test
    void validationCanBeDisabledAndRawJsonIsAvailable() {
        int id = producer(OrderV2.class).schemaId();
        byte[] invalid = forged(id, "{\"id\":1,\"amount\":-3}");
        OrderV2 lenient = JsonSchemaDeserializer.builder(client).validate(false).build().deserialize(invalid, OrderV2.class);
        assertNull(lenient.customer());
        JsonNode raw = JsonSchemaDeserializer.builder(client).build().deserialize(invalid, JsonNode.class);
        assertEquals(-3, raw.get("amount").asInt());
    }

    @Test
    void consumerEnforcesExpectedSubject() {
        byte[] message = JsonSchemaSerializer.builder(client).subject("payments-value").forType(Payment.class)
                .autoRegister(true).build().serialize(new Payment(7));

        JsonSchemaDeserializer consumer = JsonSchemaDeserializer.builder(client).expectedSubject("orders-value").build();
        SerdeException e = assertThrows(SerdeException.class, () -> consumer.deserialize(message, Payment.class));
        assertTrue(e.getMessage().contains("not registered under subject 'orders-value'"), e.getMessage());

        DeserializedMessage<Payment> any = JsonSchemaDeserializer.builder(client).build()
                .deserializeWithMetadata(message, Payment.class);
        assertNull(any.subject());
        assertEquals(new Payment(7), any.value());
    }

    @Test
    void serializerWithoutAutoRegisterRequiresRegisteredSchema() {
        JsonSchemaSerializer producer = JsonSchemaSerializer.builder(client)
                .subject("orders-value").forType(Order.class).build();
        assertThrows(RuntimeException.class, () -> producer.serialize(new Order(1, "a", BigDecimal.ONE)));
        producer(Order.class).schemaId(); // registers it
        assertEquals(1, producer.schemaVersion());
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
