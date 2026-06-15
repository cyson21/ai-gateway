package com.example.gateway.auth;

/**
 * Authentication view of an API key joined to its owning tenant. The filter authorizes a
 * request only when both the key and the tenant are {@code ACTIVE}; every other combination
 * (missing, revoked key, suspended tenant) is treated as unauthorized.
 */
public record ApiKeyRecord(String tenantId, String keyStatus, String tenantStatus) {

    /** Status value that marks a usable key or tenant; everything else is rejected. */
    public static final String ACTIVE = "ACTIVE";

    public boolean isActive() {
        return ACTIVE.equals(keyStatus) && ACTIVE.equals(tenantStatus);
    }
}
