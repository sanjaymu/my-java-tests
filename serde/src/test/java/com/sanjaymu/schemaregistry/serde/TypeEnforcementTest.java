package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanjaymu.schemaregistry.client.SchemaRegistryClient;
import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sanjaymu.schemaregistry.client.model.SchemaType;
import com.sanjaymu.schemaregistry.local.LocalSchemaRegistryServer;
import com.sanjaymu.schemaregistry.serde.model.Address;
import com.sanjaymu.schemaregistry.serde.model.OrderV1;
import com.sanjaymu.schemaregistry.serde.model.OrderV2;
import com.sanjaymu.schemaregistry.serde.model.OrderV3;
import com.sanjaymu.schemaregistry.serde.model.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeEnforcementTest {

    static final ObjectMapper MAPPER = SerdeMappers.defaultMapper();
    static final SchemaTypeChecker CHECKER = new SchemaTypeChecker(MAPPER);

    static OrderV1 sampleV1() {
        return new OrderV1(1, "acme", new BigDecimal("25.50"), Status.PAID, UUID.randomUUID(),
                Instant.parse("2026-11-12T10:00:00Z"), new Address("Pune", "IN"), List.of("rush"), Map.of("sku-1", 2));
    }

    static OrderV2 sampleV2() {
        return new OrderV2(2, "globex", new BigDecimal("10"), Status.NEW, null, null, null, List.of(), Map.of(), "SAVE10");
    }

    // ---- consumer models with deliberate mismatches

    public record MissingCustomer(long id, BigDecimal amount) {
    }

    public record AmountAsPrimitiveInt(long id, String customer, int amount) {
    }

    public enum ShortStatus { NEW, PAID }

    public record StatusMismatch(long id, String customer, ShortStatus status) {
    }

    public record TagsAsString(long id, String customer, String tags) {
    }

    public record QuantitiesAsStrings(long id, String customer, Map<String, Boolean> quantities) {
    }

    @Nested
    class Generator {
        @Test
        void requiredAndNullableRules() throws Exception {
            var schema = JavaTypeSchemaGenerator.generateNode(OrderV1.class);
            assertEquals(List.of("customer", "id"), MAPPER.convertValue(schema.get("required"), List.class));
            assertEquals("integer", schema.at("/properties/id/type").asText());
            assertEquals("[\"number\",\"null\"]", schema.at("/properties/amount/type").toString());
            assertEquals("uuid", schema.at("/properties/trackingId/format").asText());
            assertEquals("integer", schema.at("/properties/quantities/additionalProperties/type").asText());
            assertEquals("OrderV1", schema.get("title").asText());
        }

        @Test
        void generatedSchemaValidatesInstancesOfTheType() throws Exception {
            JsonSchemaValidator validator = JsonSchemaValidator.compile(JavaTypeSchemaGenerator.generate(OrderV1.class));
            assertEquals(List.of(), validator.validate(MAPPER.valueToTree(sampleV1())));
            assertEquals(List.of(), validator.validate(MAPPER.valueToTree(
                    new OrderV1(1, "acme", null, null, null, null, null, null, null))));
            assertFalse(validator.validate(MAPPER.readTree("{\"id\":1}")).isEmpty(), "customer is required");
        }

        @Test
        void aTypeAlwaysMatchesItsOwnSchema() {
            for (Class<?> c : List.of(OrderV1.class, OrderV2.class, OrderV3.class, Address.class)) {
                TypeCheckReport r = CHECKER.check(JavaTypeSchemaGenerator.generateNode(c), c);
                assertTrue(r.isClean(), c + ": " + r.format());
            }
        }
    }

    @Nested
    class Checker {
        final String v2Schema = JavaTypeSchemaGenerator.generate(OrderV2.class);

        @Test
        void olderModelGetsOnlyAWarningForNewOptionalField() {
            TypeCheckReport r = CHECKER.check(v2Schema, OrderV1.class);
            assertFalse(r.hasErrors(), r.format());
            assertEquals(1, r.warnings().size(), r.format());
            assertTrue(r.warnings().get(0).startsWith("$.coupon: optional schema property"), r.format());
        }

        @Test
        void newerModelGetsOnlyAWarningForFieldTheOldSchemaLacks() {
            TypeCheckReport r = CHECKER.check(JavaTypeSchemaGenerator.generate(OrderV1.class), OrderV2.class);
            assertFalse(r.hasErrors(), r.format());
            assertTrue(r.warnings().get(0).contains("$.coupon: Java property"), r.format());
        }

        @Test
        void missingRequiredPropertyIsAnError() {
            TypeCheckReport r = CHECKER.check(v2Schema, MissingCustomer.class);
            assertTrue(r.errors().contains(
                    "$.customer: required by the schema but MissingCustomer has no such property; its value would be lost"),
                    r.format());
        }

        @Test
        void decimalIntoIntIsAnError() {
            TypeCheckReport r = CHECKER.check(v2Schema, AmountAsPrimitiveInt.class);
            assertTrue(r.errors().stream().anyMatch(e -> e.startsWith("$.amount: schema allows null but Java type is primitive int")), r.format());
            assertTrue(r.errors().stream().anyMatch(e -> e.startsWith("$.amount: schema type 'number' allows decimals")), r.format());
        }

        @Test
        void enumValuesMustAllExist() {
            TypeCheckReport r = CHECKER.check(v2Schema, StatusMismatch.class);
            assertEquals(List.of("$.status: schema enum values [SHIPPED] are not defined in Java enum ShortStatus"), r.errors());
        }

        @Test
        void structuralMismatches() {
            assertTrue(CHECKER.check(v2Schema, TagsAsString.class).errors()
                    .contains("$.tags: schema type 'array' cannot be mapped to Java type String"));
            assertTrue(CHECKER.check(v2Schema, QuantitiesAsStrings.class).errors()
                    .contains("$.quantities{}: schema type 'integer' cannot be mapped to Java type Boolean"));
        }

        @Test
        void closedSchemasRejectUnknownJavaProperties() {
            String closed = """
                    {"type":"object","additionalProperties":false,
                     "properties":{"id":{"type":"integer"},"customer":{"type":"string"}},"required":["id","customer"]}""";
            assertEquals(List.of("$.amount: Java property of AmountAsPrimitiveInt is not allowed by the schema "
                    + "(additionalProperties: false)"), CHECKER.check(closed, AmountAsPrimitiveInt.class).errors());
        }

        @Test
        void resolvesLocalRefsAndRecursion() {
            String schema = """
                    {"definitions":{"addr":{"type":"object","properties":{"city":{"type":"integer"}}}},
                     "type":"object","properties":{"shippingAddress":{"$ref":"#/definitions/addr"}}}""";
            TypeCheckReport r = CHECKER.check(schema, OrderV1.class);
            assertTrue(r.errors().contains("$.shippingAddress.city: schema type 'integer' cannot be mapped to Java type String"),
                    r.format());
        }
    }

    @Nested
    class EndToEnd {
        LocalSchemaRegistryServer server;
        SchemaRegistryClient client;

        @BeforeEach
        void start() throws Exception {
            server = LocalSchemaRegistryServer.start(0);
            client = SchemaRegistryClient.builder().baseUrl(server.url()).maxRetries(0).build();
        }

        @AfterEach
        void stop() {
            server.close();
        }

        JsonSchemaSerializer producer(Class<?> type) {
            return JsonSchemaSerializer.builder(client).subject("orders-value").forType(type).autoRegister(true).build();
        }

        @Test
        void producersRegisterSchemasGeneratedFromTheirModels() {
            JsonSchemaSerializer v1 = producer(OrderV1.class);
            JsonSchemaSerializer v2 = producer(OrderV2.class);
            byte[] m1 = v1.serialize(sampleV1());
            byte[] m2 = v2.serialize(sampleV2());
            assertEquals(1, v1.schemaVersion());
            assertEquals(2, v2.schemaVersion());
            assertEquals(JavaTypeSchemaGenerator.generate(OrderV1.class), client.getSchemaById(v1.schemaId()).schema());

            JsonSchemaDeserializer consumer = JsonSchemaDeserializer.builder(client)
                    .expectedSubject("orders-value").targetType(OrderV2.class).build();
            assertEquals(Map.of(1, 1, 2, 0), Map.of(
                    1, consumer.startupTypeReports().get(1).warnings().size(),
                    2, consumer.startupTypeReports().get(2).warnings().size()));
            OrderV2 fromV1 = consumer.deserialize(m1, OrderV2.class);
            assertEquals(sampleV1().customer(), fromV1.customer());
            assertEquals(new BigDecimal("25.50"), fromV1.amount(), "decimals keep their exact value and scale");
            assertEquals(sampleV1().createdAt(), fromV1.createdAt());
            assertEquals(sampleV1().shippingAddress(), fromV1.shippingAddress());
            assertEquals(sampleV2(), consumer.deserialize(m2, OrderV2.class));
        }

        @Test
        void serializerOnlyAcceptsItsType() {
            JsonSchemaSerializer v1 = producer(OrderV1.class);
            assertThrows(IllegalArgumentException.class, () -> v1.serialize(sampleV2()));
        }

        @Test
        void consumerWithMismatchedModelFailsAtStartup() {
            producer(OrderV1.class).serialize(sampleV1());
            SchemaTypeMismatchException e = assertThrows(SchemaTypeMismatchException.class, () ->
                    JsonSchemaDeserializer.builder(client).expectedSubject("orders-value")
                            .targetType(AmountAsPrimitiveInt.class).build());
            assertTrue(e.getMessage().contains("does not match subject 'orders-value' v1"), e.getMessage());
            assertTrue(e.report().errors().stream().allMatch(m -> m.startsWith("v1 $.amount")), e.report().format());
        }

        @Test
        void strictModeFailsOnWarnings() {
            producer(OrderV2.class).serialize(sampleV2());
            assertThrows(SchemaTypeMismatchException.class, () -> JsonSchemaDeserializer.builder(client)
                    .expectedSubject("orders-value").targetType(OrderV1.class).failOnTypeWarnings(true).build());
            JsonSchemaDeserializer.builder(client).expectedSubject("orders-value").targetType(OrderV1.class).build();
        }

        @Test
        void consumerDetectsIncompatibleSchemaPublishedAfterStartup() {
            byte[] m1 = producer(OrderV1.class).serialize(sampleV1());
            JsonSchemaDeserializer consumer = JsonSchemaDeserializer.builder(client)
                    .expectedSubject("orders-value").targetType(OrderV2.class).build();
            assertEquals("acme", consumer.deserialize(m1, OrderV2.class).customer());

            // A producer later publishes v3 with a new required field the consumer's model doesn't have.
            byte[] m3 = producer(OrderV3.class).serialize(new OrderV3(3, "initech", null, null, null, null, null,
                    null, null, null, 7));
            SchemaTypeMismatchException e = assertThrows(SchemaTypeMismatchException.class,
                    () -> consumer.deserialize(m3, OrderV2.class));
            assertTrue(e.report().errors().get(0).startsWith("$.priority: required by the schema"), e.getMessage());
        }

        @Test
        void subjectNotRegisteredYetIsCheckedLazily() {
            JsonSchemaDeserializer consumer = JsonSchemaDeserializer.builder(client)
                    .expectedSubject("orders-value").targetType(OrderV1.class).build();
            assertTrue(consumer.startupTypeReports().isEmpty());
            client.register("orders-value", RegisterSchemaRequest.of(
                    "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}", SchemaType.JSON));
            byte[] m = WireFormat.encode(1, "{\"id\":\"x\"}".getBytes());
            assertThrows(SchemaTypeMismatchException.class, () -> consumer.deserialize(m, OrderV1.class));
        }
    }
}
