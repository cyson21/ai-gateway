package com.example.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.api.GatewayPipeline;
import com.example.gateway.api.PipelineMode;
import com.example.gateway.cache.CacheType;
import com.example.gateway.guardrail.GuardrailResult;
import com.example.gateway.observability.RequestRecord;
import com.example.gateway.provider.CompletionRequest;

import org.junit.jupiter.api.Test;

class GatewayPipelineConfigTest {

    private final GatewayPipeline pipeline = new GatewayPipelineConfig().gatewayPipeline();

    @Test
    void defaultRuntimeUsesRealCacheInsteadOfNoOpCache() {
        CompletionRequest request = new CompletionRequest(
                "tenant-1", "gpt-4o", "Summarize tenant budget controls", 64, false);

        RequestRecord first = pipeline.handle(request, PipelineMode.ROUTED_RESILIENT);
        RequestRecord second = pipeline.handle(request, PipelineMode.ROUTED_RESILIENT);

        assertThat(first.servedFromCache()).isFalse();
        assertThat(first.chosenProvider()).isNotNull().isNotEqualTo("cache");
        assertThat(second.servedFromCache()).isTrue();
        assertThat(second.cacheType()).isEqualTo(CacheType.EXACT);
        assertThat(second.chosenProvider()).isEqualTo("cache");
        assertThat(second.cost()).isZero();
    }

    @Test
    void defaultRuntimeAppliesRuleBasedInputGuardrail() {
        CompletionRequest request = new CompletionRequest(
                "tenant-1", "gpt-4o", "Please include forbidden deployment details", 64, false);

        RequestRecord record = pipeline.handle(request, PipelineMode.ROUTED_RESILIENT);

        assertThat(record.guardrailResult()).isEqualTo(GuardrailResult.BLOCKED_INPUT);
        assertThat(record.chosenProvider()).isNull();
        assertThat(record.message()).contains("blocked by input guardrail");
    }
}
