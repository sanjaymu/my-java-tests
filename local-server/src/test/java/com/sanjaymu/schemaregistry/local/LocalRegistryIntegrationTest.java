package com.sanjaymu.schemaregistry.local;

import com.sanjaymu.schemaregistry.client.SchemaRegistryClient;
import com.sanjaymu.schemaregistry.client.SchemaRegistryException;
import com.sanjaymu.schemaregistry.client.model.CompatibilityLevel;
import com.sanjaymu.schemaregistry.client.model.CompatibilityResult;
import com.sanjaymu.schemaregistry.client.model.Mode;
import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sanjaymu.schemaregistry.client.model.SchemaReference;
import com.sanjaymu.schemaregistry.client.model.SchemaType;
import com.sanjaymu.schemaregistry.client.model.SchemaVersion;
import com.sanjaymu.schemaregistry.client.model.SubjectVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests: SDK client talking to the local in-memory server. */
class LocalRegistryIntegrationTest {

    static final String USER_V1 = """
            {"type":"record","name":"User","namespace":"com.example","fields":[
              {"name":"id","type":"long"},
              {"name":"name","type":"string"}]}""";

    /** Adds an optional field with a default: backward and forward compatible. */
    static final String USER_V2 = """
            {"type":"record","name":"User","namespace":"com.example","fields":[
              {"name":"id","type":"long"},
              {"name":"name","type":"string"},
              {"name":"email","type":["null","string"],"default":null}]}""";

    /** Adds a required field without default: not backward compatible. */
    static final String USER_BAD = """
            {"type":"record","name":"User","namespace":"com.example","fields":[
              {"name":"id","type":"long"},
              {"name":"name","type":"string"},
              {"name":"age","type":"int"}]}""";

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
    void registerFetchAndDeduplicate() {
        int id = client.register("users-value", RegisterSchemaRequest.avro(USER_V1));
        assertEquals(id, client.register("users-value", RegisterSchemaRequest.avro(USER_V1)), "re-register is idempotent");
        assertEquals(id, client.register("other-value", RegisterSchemaRequest.avro(USER_V1)), "ids are global");

        assertEquals(List.of("other-value", "users-value"), client.getSubjects());
        assertEquals(List.of(1), client.getVersions("users-value"));

        SchemaVersion latest = client.getLatestSchema("users-value");
        assertEquals(id, latest.id());
        assertEquals(1, latest.version());
        assertEquals(SchemaType.AVRO, latest.type());
        assertTrue(client.getRawSchema("users-value", "1").contains("\"User\""));
        assertTrue(client.getSchemaById(id).schema().contains("\"User\""));
        assertEquals(List.of(new SubjectVersion("other-value", 1), new SubjectVersion("users-value", 1)),
                client.getSubjectVersionsById(id));
    }

    @Test
    void enforcesBackwardCompatibility() {
        client.register("users-value", RegisterSchemaRequest.avro(USER_V1));

        CompatibilityResult bad = client.testCompatibility("users-value", "latest",
                RegisterSchemaRequest.avro(USER_BAD), true);
        assertFalse(bad.compatible());
        assertTrue(bad.messages().get(0).contains("age"), bad.messages().toString());

        SchemaRegistryException e = assertThrows(SchemaRegistryException.class,
                () -> client.register("users-value", RegisterSchemaRequest.avro(USER_BAD)));
        assertEquals(SchemaRegistryException.INCOMPATIBLE_SCHEMA, e.errorCode());

        assertTrue(client.testCompatibility("users-value", RegisterSchemaRequest.avro(USER_V2), false).compatible());
        client.register("users-value", RegisterSchemaRequest.avro(USER_V2));
        assertEquals(List.of(1, 2), client.getVersions("users-value"));
    }

    @Test
    void forwardAndTransitiveLevels() {
        client.setSubjectCompatibility("users-value", CompatibilityLevel.FORWARD);
        client.register("users-value", RegisterSchemaRequest.avro(USER_BAD));
        // Removing a field that has no default in the old (reader) schema breaks forward compatibility.
        SchemaRegistryException e = assertThrows(SchemaRegistryException.class,
                () -> client.register("users-value", RegisterSchemaRequest.avro(USER_V1)));
        assertEquals(409, e.errorCode());

        client.setSubjectCompatibility("users-value", CompatibilityLevel.NONE);
        client.register("users-value", RegisterSchemaRequest.avro(USER_V1));
        client.setSubjectCompatibility("users-value", CompatibilityLevel.BACKWARD_TRANSITIVE);
        // V2 can read V1 data but not USER_BAD data (missing "age" in reader is fine, but check transitive against v1 too).
        assertTrue(client.testCompatibility("users-value", RegisterSchemaRequest.avro(USER_V2), true).compatible());
        assertEquals(CompatibilityLevel.BACKWARD_TRANSITIVE, client.getSubjectCompatibility("users-value", false));
        assertEquals(CompatibilityLevel.BACKWARD_TRANSITIVE, client.deleteSubjectCompatibility("users-value"));
        assertEquals(CompatibilityLevel.BACKWARD, client.getSubjectCompatibility("users-value", true));
    }

    @Test
    void lookupFindsRegisteredSchema() {
        int id = client.register("users-value", RegisterSchemaRequest.avro(USER_V1));
        // Whitespace differences are normalised.
        SchemaVersion found = client.lookup("users-value", RegisterSchemaRequest.avro(USER_V1.replace("\n", " ")));
        assertEquals(id, found.id());

        SchemaRegistryException e = assertThrows(SchemaRegistryException.class,
                () -> client.lookup("users-value", RegisterSchemaRequest.avro(USER_V2)));
        assertEquals(SchemaRegistryException.SCHEMA_NOT_FOUND, e.errorCode());
    }

    @Test
    void softAndHardDeletes() {
        client.register("users-value", RegisterSchemaRequest.avro(USER_V1));
        client.register("users-value", RegisterSchemaRequest.avro(USER_V2));

        assertEquals(2, client.deleteSchemaVersion("users-value", "latest", false));
        assertEquals(List.of(1), client.getVersions("users-value"));
        assertEquals(List.of(1, 2), client.getVersions("users-value", true));

        SchemaRegistryException notSoft = assertThrows(SchemaRegistryException.class,
                () -> client.deleteSubject("users-value", true));
        assertEquals(SchemaRegistryException.SUBJECT_NOT_SOFT_DELETED, notSoft.errorCode());

        assertEquals(List.of(1), client.deleteSubject("users-value", false));
        assertEquals(List.of(), client.getSubjects());
        assertEquals(List.of("users-value"), client.getSubjects(null, true));
        assertEquals(List.of(1, 2), client.deleteSubject("users-value", true));
        assertEquals(List.of(), client.getSubjects(null, true));

        SchemaRegistryException gone = assertThrows(SchemaRegistryException.class,
                () -> client.getVersions("users-value"));
        assertEquals(SchemaRegistryException.SUBJECT_NOT_FOUND, gone.errorCode());
    }

    @Test
    void referencesAndOtherSchemaTypes() {
        String address = """
                {"type":"record","name":"Address","namespace":"com.example","fields":[{"name":"city","type":"string"}]}""";
        client.register("address", RegisterSchemaRequest.avro(address));
        String customer = """
                {"type":"record","name":"Customer","namespace":"com.example","fields":[
                  {"name":"address","type":"com.example.Address"}]}""";
        int customerId = client.register("customer-value", new RegisterSchemaRequest(customer, null,
                List.of(new SchemaReference("com.example.Address", "address", 1))));
        assertEquals(List.of(customerId), client.getReferencedBy("address", "1"));
        assertEquals(1, client.getSchemaById(customerId).references().size());

        int jsonId = client.register("json-value", RegisterSchemaRequest.of("{\"type\":\"object\"}", SchemaType.JSON));
        assertEquals(SchemaType.JSON, client.getSchemaById(jsonId).type());
        int protoId = client.register("proto-value", RegisterSchemaRequest.of(
                "syntax = \"proto3\"; message M { string a = 1; }", SchemaType.PROTOBUF));
        assertNotEquals(jsonId, protoId);
        assertEquals(List.of("JSON", "PROTOBUF", "AVRO"), client.getSchemaTypes());
    }

    @Test
    void rejectsInvalidSchemas() {
        SchemaRegistryException e = assertThrows(SchemaRegistryException.class,
                () -> client.register("x", RegisterSchemaRequest.avro("{\"type\":\"record\",\"name\":\"X\"}")));
        assertEquals(SchemaRegistryException.INVALID_SCHEMA, e.errorCode());
        assertEquals(List.of(), client.getSubjects(null, true), "failed registration must not create the subject");

        SchemaRegistryException badVersion = assertThrows(SchemaRegistryException.class,
                () -> client.getSchemaVersion("x", "abc"));
        assertEquals(404, badVersion.httpStatus(), "unknown subject takes precedence");
    }

    @Test
    void readOnlyModeBlocksWrites() {
        assertEquals(Mode.READWRITE, client.getGlobalMode());
        client.setSubjectMode("locked", Mode.READONLY);
        SchemaRegistryException e = assertThrows(SchemaRegistryException.class,
                () -> client.register("locked", RegisterSchemaRequest.avro("\"string\"")));
        assertEquals(SchemaRegistryException.OPERATION_NOT_PERMITTED, e.errorCode());
        assertEquals(Mode.READONLY, client.getSubjectMode("locked", false));
        assertEquals(Mode.READONLY, client.deleteSubjectMode("locked"));
        client.register("locked", RegisterSchemaRequest.avro("\"string\""));
    }

    @Test
    void subjectsWithSpecialCharacters() {
        client.register("team/orders value+v1", RegisterSchemaRequest.avro("\"string\""));
        assertEquals(List.of("team/orders value+v1"), client.getSubjects());
        assertEquals(1, client.getLatestSchema("team/orders value+v1").version());
    }
}
