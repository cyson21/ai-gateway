package com.example.gateway.auth;

import java.util.Optional;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Authenticates every gateway request by hashing the {@code Authorization: Bearer <key>} token
 * and resolving it to an active tenant. Missing token, unknown/typo key, revoked key, or
 * suspended tenant all map to {@code 401}. On success the resolved tenant id is published both as
 * an exchange attribute and on the Reactor context for downstream pipeline stages.
 *
 * <p>Runs at highest precedence so unauthenticated traffic never reaches the pipeline. Actuator
 * endpoints (health/info) are exempt so liveness probes do not need a key.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiKeyAuthFilter implements WebFilter {

    /** Exchange attribute carrying the resolved tenant id to downstream handlers. */
    public static final String TENANT_ID_ATTRIBUTE = "gateway.tenantId";

    /** Reactor context key carrying the resolved tenant id. */
    public static final String TENANT_ID_CONTEXT_KEY = "gateway.tenantId";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String EXEMPT_PREFIX = "/actuator";

    private final ApiKeyRepository repository;

    public ApiKeyAuthFilter(ApiKeyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isExempt(exchange)) {
            return chain.filter(exchange);
        }
        String token = bearerToken(exchange);
        if (token == null) {
            return unauthorized(exchange);
        }
        // map/defaultIfEmpty collapse "not found" and "inactive" into one Optional carrier so a
        // single flatMap drives both branches; a switchIfEmpty here would also fire on the
        // authorized path because chain.filter returns an empty Mono<Void>.
        return repository.findByKeyHash(ApiKeyHasher.sha256Hex(token))
                .filter(ApiKeyRecord::isActive)
                .map(record -> Optional.of(record.tenantId()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(tenant -> tenant
                        .map(tenantId -> authorized(exchange, chain, tenantId))
                        .orElseGet(() -> unauthorized(exchange)));
    }

    private boolean isExempt(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value().startsWith(EXEMPT_PREFIX);
    }

    private String bearerToken(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private Mono<Void> authorized(ServerWebExchange exchange, WebFilterChain chain, String tenantId) {
        exchange.getAttributes().put(TENANT_ID_ATTRIBUTE, tenantId);
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(TENANT_ID_CONTEXT_KEY, tenantId));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
