package com.stopbell.transit.client;

import com.stopbell.transit.domain.TransitProvider;

public final class TransitProviderClientException extends RuntimeException {

    public static final String GBIS_SOURCE = "GBIS";

    private final TransitProviderClientFailureKind failureKind;
    private final TransitProvider provider;
    private final String source;
    private final String operation;
    private final Integer httpStatus;
    private final String providerResultCode;

    private TransitProviderClientException(
            TransitProviderClientFailureKind failureKind,
            TransitProvider provider,
            String source,
            String operation,
            Integer httpStatus,
            String providerResultCode,
            Throwable cause
    ) {
        super("Transit provider request failed: provider=%s, operation=%s, kind=%s"
                .formatted(source, operation, failureKind), cause);
        this.failureKind = failureKind;
        this.provider = provider;
        this.source = source;
        this.operation = operation;
        this.httpStatus = httpStatus;
        this.providerResultCode = providerResultCode;
    }

    public static TransitProviderClientException transport(
            TransitProvider provider, String operation, Throwable cause
    ) {
        return new TransitProviderClientException(
                TransitProviderClientFailureKind.TRANSPORT, provider, provider.name(), operation, null, null, cause
        );
    }

    public static TransitProviderClientException http(
            TransitProvider provider, String operation, int httpStatus, Throwable cause
    ) {
        return new TransitProviderClientException(
                TransitProviderClientFailureKind.HTTP, provider, provider.name(), operation, httpStatus, null, cause
        );
    }

    public static TransitProviderClientException provider(
            TransitProvider provider, String operation, String providerResultCode
    ) {
        return new TransitProviderClientException(
                TransitProviderClientFailureKind.PROVIDER, provider, provider.name(), operation, null, providerResultCode, null
        );
    }

    public static TransitProviderClientException protocol(
            TransitProvider provider, String operation, Throwable cause
    ) {
        return new TransitProviderClientException(
                TransitProviderClientFailureKind.PROTOCOL, provider, provider.name(), operation, null, null, cause
        );
    }

    /** GBIS location failures. GBIS is the Gyeonggi source behind TAGO, so {@link #provider()} stays TAGO. */
    public static TransitProviderClientException gbisTransport(String operation, Throwable cause) {
        return new TransitProviderClientException(TransitProviderClientFailureKind.TRANSPORT,
                TransitProvider.TAGO, GBIS_SOURCE, operation, null, null, cause);
    }

    public static TransitProviderClientException gbisHttp(String operation, int httpStatus, Throwable cause) {
        return new TransitProviderClientException(TransitProviderClientFailureKind.HTTP,
                TransitProvider.TAGO, GBIS_SOURCE, operation, httpStatus, null, cause);
    }

    public static TransitProviderClientException gbisProvider(String operation, String providerResultCode) {
        return new TransitProviderClientException(TransitProviderClientFailureKind.PROVIDER,
                TransitProvider.TAGO, GBIS_SOURCE, operation, null, providerResultCode, null);
    }

    public static TransitProviderClientException gbisProtocol(String operation, Throwable cause) {
        return new TransitProviderClientException(TransitProviderClientFailureKind.PROTOCOL,
                TransitProvider.TAGO, GBIS_SOURCE, operation, null, null, cause);
    }

    /** Label of the data source for logs: a {@link TransitProvider} name or {@code GBIS}. */
    public String source() {
        return source;
    }

    public TransitProviderClientFailureKind failureKind() {
        return failureKind;
    }

    public TransitProvider provider() {
        return provider;
    }

    public String operation() {
        return operation;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String providerResultCode() {
        return providerResultCode;
    }
}
