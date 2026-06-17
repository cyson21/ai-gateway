package com.example.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.CompletionResponse;
import com.example.gateway.provider.Usage;

import org.junit.jupiter.api.Test;

class VersionedSemanticCacheTest {

    private static CompletionRequest request(String alias, String prompt) {
        return new CompletionRequest("tenant-1", alias, prompt, 64, false);
    }

    @Test
    void invalidatingTenantAliasMakesOlderEntriesUnreachableWithoutAffectingOtherAliases() {
        InMemoryCacheInvalidationPolicy policy = new InMemoryCacheInvalidationPolicy();
        VersionedSemanticCache cache = new VersionedSemanticCache(
                new ExactCache(new InMemoryCacheStore()), policy);
        CompletionRequest target = request("gpt-4o", "summarize policy");
        CompletionResponse first = new CompletionResponse("old answer", "gpt-4o", new Usage(2, 3));
        CompletionRequest otherAlias = request("fast", "summarize policy");
        CompletionResponse other = new CompletionResponse("fast answer", "fast", new Usage(2, 3));

        cache.store(target, first);
        cache.store(otherAlias, other);
        assertThat(cache.lookup(target).response()).isEqualTo(first);

        policy.invalidate("tenant-1", "gpt-4o");

        assertThat(cache.lookup(target).hit()).isFalse();
        assertThat(cache.lookup(otherAlias).response()).isEqualTo(other);

        CompletionResponse refreshed = new CompletionResponse("new answer", "gpt-4o", new Usage(2, 4));
        cache.store(target, refreshed);

        assertThat(cache.lookup(target).response()).isEqualTo(refreshed);
    }
}
