package com.sanjaymu.schemaregistry.serde;

/**
 * A deserialized message together with the schema it was written with.
 *
 * @param schemaId the schema id from the message header
 * @param subject  the subject the schema was resolved under ({@code null} if not resolved)
 * @param version  the subject version ({@code null} if not resolved)
 * @param value    the deserialized value
 */
public record DeserializedMessage<T>(int schemaId, String subject, Integer version, T value) {
}
