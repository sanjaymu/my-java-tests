package com.sanjaymu.schemaregistry.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvroCompatibilityCheckerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode j(String s) throws Exception {
        return M.readTree(s);
    }

    private static boolean canRead(String reader, String writer) throws Exception {
        return AvroCompatibilityChecker.canRead(j(reader), j(writer)).isEmpty();
    }

    @Test
    void primitivePromotions() throws Exception {
        assertTrue(canRead("\"long\"", "\"int\""));
        assertTrue(canRead("\"double\"", "\"float\""));
        assertTrue(canRead("\"bytes\"", "\"string\""));
        assertFalse(canRead("\"int\"", "\"long\""));
        assertFalse(canRead("\"string\"", "\"int\""));
    }

    @Test
    void unions() throws Exception {
        assertTrue(canRead("[\"null\",\"string\"]", "\"string\""));
        assertTrue(canRead("[\"null\",\"long\"]", "[\"null\",\"int\"]"));
        assertFalse(canRead("\"string\"", "[\"null\",\"string\"]"));
    }

    @Test
    void enums() throws Exception {
        String ab = "{\"type\":\"enum\",\"name\":\"E\",\"symbols\":[\"A\",\"B\"]}";
        String abc = "{\"type\":\"enum\",\"name\":\"E\",\"symbols\":[\"A\",\"B\",\"C\"]}";
        String abDefault = "{\"type\":\"enum\",\"name\":\"E\",\"symbols\":[\"A\",\"B\"],\"default\":\"A\"}";
        assertTrue(canRead(abc, ab));
        assertFalse(canRead(ab, abc));
        assertTrue(canRead(abDefault, abc));
    }

    @Test
    void recordsWithAliasesArraysAndMaps() throws Exception {
        String writer = """
                {"type":"record","name":"R","fields":[
                  {"name":"old_name","type":"int"},
                  {"name":"tags","type":{"type":"array","items":"string"}},
                  {"name":"attrs","type":{"type":"map","values":"int"}}]}""";
        String reader = """
                {"type":"record","name":"R","fields":[
                  {"name":"new_name","aliases":["old_name"],"type":"long"},
                  {"name":"tags","type":{"type":"array","items":"string"}},
                  {"name":"attrs","type":{"type":"map","values":"long"}}]}""";
        assertTrue(canRead(reader, writer));
        String badReader = reader.replace("\"values\":\"long\"", "\"values\":\"string\"");
        assertEquals(1, AvroCompatibilityChecker.canRead(j(badReader), j(writer)).size());
    }

    @Test
    void recursiveTypes() throws Exception {
        String list = """
                {"type":"record","name":"Node","fields":[
                  {"name":"value","type":"int"},
                  {"name":"next","type":["null","Node"],"default":null}]}""";
        AvroCompatibilityChecker.validate(j(list));
        assertTrue(canRead(list, list));
    }

    @Test
    void validation() {
        assertThrows(RegistryError.class, () -> AvroCompatibilityChecker.validate(j("\"strin\"")));
        assertThrows(RegistryError.class, () -> AvroCompatibilityChecker.validate(
                j("{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"a\",\"type\":\"int\"},{\"name\":\"a\",\"type\":\"int\"}]}")));
        assertThrows(RegistryError.class, () -> AvroCompatibilityChecker.validate(j("{\"type\":\"array\"}")));
    }
}
