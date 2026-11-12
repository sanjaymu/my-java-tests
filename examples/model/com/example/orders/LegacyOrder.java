package com.example.orders;

/** Example of a consumer model that does NOT match the Order schema (int amount, no customer). */
public record LegacyOrder(long id, int amount) {
}
