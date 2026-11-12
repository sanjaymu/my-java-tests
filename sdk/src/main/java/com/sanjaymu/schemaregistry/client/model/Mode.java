package com.sanjaymu.schemaregistry.client.model;

/** Registry operating modes. */
public enum Mode {
    READWRITE, READONLY, IMPORT;

    public static Mode parse(String value) {
        return Mode.valueOf(value.trim().toUpperCase());
    }
}
