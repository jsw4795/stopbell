package com.stopbell.transit.domain;

/** Fixed messages avoid persisting provider responses, credentials or exception text. */
public enum TransitCollectionFailureCode {
    SOURCE_UNAVAILABLE("Source unavailable"),
    INVALID_SOURCE("Source validation failed"),
    UNSAFE_CHANGE("Unsafe metadata change blocked"),
    APPROVAL_MISMATCH("Change approval unavailable or mismatched"),
    REVIEW_SAVE_FAILED("Unsafe change review evidence storage failed"),
    SYNC_FAILED("Database synchronization failed"),
    UNEXPECTED_FAILURE("Unexpected collection failure");

    private final String message;

    TransitCollectionFailureCode(String message) { this.message = message; }
    public String message() { return message; }
}
