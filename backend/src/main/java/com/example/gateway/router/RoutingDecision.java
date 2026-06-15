package com.example.gateway.router;

/**
 * The router's recorded decision for one request. Stored per request so routing
 * is never a black box.
 */
public record RoutingDecision(String alias, RoutingStrategy strategy, String provider, String model, String reason) {
}
