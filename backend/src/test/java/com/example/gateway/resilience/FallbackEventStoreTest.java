package com.example.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * M4-1: the {@link FallbackEventStore} append/projection/ordering contract, proven against the
 * in-memory fake (same store-port shape as the D1 {@code RequestLogStore} slice). Real
 * {@code fallback_events} INSERT and the {@code request_id} FK are a later persistence slice.
 */
class FallbackEventStoreTest {

    @Test
    void appendAccumulatesInOrderPerRequest() {
        FallbackEventStore store = new InMemoryFallbackEventStore();

        store.append(1L, FallbackEvent.failed(0, "p-a", "m-a", "p-a unhealthy"));
        store.append(1L, FallbackEvent.success(1, "p-b", "m-b"));
        store.append(2L, FallbackEvent.success(0, "p-c", "m-c"));

        List<FallbackEvent> r1 = store.forRequest(1L);
        assertThat(r1).hasSize(2);
        assertThat(r1.get(0).attemptNo()).isZero();
        assertThat(r1.get(0).outcome()).isEqualTo(FallbackOutcome.FAILED);
        assertThat(r1.get(0).provider()).isEqualTo("p-a");
        assertThat(r1.get(0).model()).isEqualTo("m-a");
        assertThat(r1.get(0).errorType()).isEqualTo("p-a unhealthy");
        assertThat(r1.get(1).attemptNo()).isEqualTo(1);
        assertThat(r1.get(1).outcome()).isEqualTo(FallbackOutcome.SUCCESS);
        assertThat(r1.get(1).errorType()).isNull();

        assertThat(store.forRequest(2L)).hasSize(1);
        assertThat(store.forRequest(99L)).isEmpty();
        assertThat(store.all()).hasSize(3);
    }

    @Test
    void appendAllRecordsAChainRunInOrder() {
        FallbackEventStore store = new InMemoryFallbackEventStore();
        List<FallbackEvent> chain = List.of(
                FallbackEvent.failed(0, "p-a", "m-a", "p-a unhealthy"),
                FallbackEvent.failed(1, "p-b", "m-b", "p-b unhealthy"),
                FallbackEvent.success(2, "p-c", "m-c"));

        store.appendAll(7L, chain);

        List<FallbackEvent> stored = store.forRequest(7L);
        assertThat(stored).containsExactlyElementsOf(chain);
        assertThat(stored).extracting(FallbackEvent::attemptNo).containsExactly(0, 1, 2);
        assertThat(stored).extracting(FallbackEvent::outcome)
                .containsExactly(FallbackOutcome.FAILED, FallbackOutcome.FAILED, FallbackOutcome.SUCCESS);
    }

    @Test
    void chainResultEventsFeedTheStore() {
        // End-to-end: a real chain run's events land as rows for a request.
        com.example.gateway.provider.FakeLlmProvider a =
                new com.example.gateway.provider.FakeLlmProvider("p-a");
        a.setHealthy(false);
        com.example.gateway.provider.FakeLlmProvider b =
                new com.example.gateway.provider.FakeLlmProvider("p-b");

        FallbackChain chain = new FallbackChain(id -> "p-a".equals(id) ? a : b);
        List<com.example.gateway.router.ModelCandidate> ordered = new com.example.gateway.router.PolicyRouter()
                .order(com.example.gateway.router.RoutingStrategy.FIXED, List.of(
                        new com.example.gateway.router.ModelCandidate("p-a", "m-a", 1000L, 10L, true),
                        new com.example.gateway.router.ModelCandidate("p-b", "m-b", 1000L, 10L, true)));
        FallbackResult result = chain.dispatch(ordered,
                new com.example.gateway.provider.CompletionRequest("t", "gpt-4o", "hi", 16, false));

        FallbackEventStore store = new InMemoryFallbackEventStore();
        store.appendAll(42L, result.events());

        assertThat(store.forRequest(42L)).hasSize(2);
        assertThat(store.forRequest(42L)).extracting(FallbackEvent::outcome)
                .containsExactly(FallbackOutcome.FAILED, FallbackOutcome.SUCCESS);
    }
}
