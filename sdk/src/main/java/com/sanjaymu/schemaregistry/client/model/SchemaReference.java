package com.sanjaymu.schemaregistry.client.model;

/**
 * A reference from one schema to another registered schema.
 *
 * @param name    the name used to import the referenced schema (e.g. an Avro full name or a .proto file name)
 * @param subject the subject the referenced schema is registered under
 * @param version the version of the referenced schema within the subject
 */
public record SchemaReference(String name, String subject, Integer version) {
}
