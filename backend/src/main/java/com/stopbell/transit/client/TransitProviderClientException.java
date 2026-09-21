package com.stopbell.transit.client;

import com.stopbell.transit.domain.TransitProvider;

public final class TransitProviderClientException extends RuntimeException {

    private final TransitProviderClientFailureKind failureKind;
    private final TransitProvider provider;
    private final String operation;
    private final Integer httpStatus;
    private final String providerResultCode;

    private TransitProviderClientException(
            TransitProviderClientFailureKind failureKind,
            TransitProvider provider,
            String operation,
            Integer httpStatus,
            String providerResultCode,
            Throwable cause
    ) {
        super("Transit provider request failed: provider=%s, operation=%s, kind=%s"
                .formatted(provider, operation, failureKind), cause);
        this.failureKind = failureKind;
        this.provider = provider;
        this.operation = operation;
        this.httpStatus = httpStatus;
        this.providerResultCode = providerResultCode;
    }

    public static TransitProviderClientException transport(
            TransitProvider provider, String operation, Throwable cause
    ) {
        return new TransitProviderClientException(
                TransitProviderClientFailureKind.TRANSPORT, provider, operation, null, null, cause
        );
    }

    public static TransitProviderClientException http(
            TransitProvider provider, String operation, int httpStatus, Throwable cause
    ) {
        return new TransitProviderClientException(
                TransitProviderClientFailureKind.HTTP, provider, operation, httpStatus, null, cause
        );
    }

    public static TransitProviderClientException provider(
            TransitProvider provider, String operation, String providerResultCode
    ) {
        return new TransitProviderClientException(
                TransitProviderClientFailureKind.PROVIDER, provider, operation, null, providerResultCode, null
        );
    }

    public static TransitProviderClientException protocol(
            TransitProvider provider, String operation, Throwable cause
    ) {
        return new TransitProviderClientException(
                TransitProviderClientFailureKind.PROTOCOL, provider, operation, null, null, cause
        );
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
