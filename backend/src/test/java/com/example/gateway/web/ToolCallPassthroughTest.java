package com.example.gateway.web;

import java.util.List;
import java.util.Map;

import com.example.gateway.api.GatewayPipeline;
import com.example.gateway.auth.ApiKeyAuthFilter;
import com.example.gateway.config.GatewayPipelineConfig;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

class ToolCallPassthroughTest {

    private final GatewayPipeline pipeline = new GatewayPipelineConfig().gatewayPipeline();

    private final WebFilter tenantFilter = (exchange, chain) -> {
        exchange.getAttributes().put(ApiKeyAuthFilter.TENANT_ID_ATTRIBUTE, "tenant-1");
        return chain.filter(exchange);
    };

    private WebTestClient client() {
        return WebTestClient.bindToController(new ChatCompletionController(pipeline))
                .webFilter(tenantFilter)
                .build();
    }

    @Test
    void toolDefinitionsPassThroughToProviderAndReturnOpenAiStyleToolCalls() {
        Map<String, Object> body = Map.of(
                "model", "gpt-4o",
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", "Use lookup_policy for PTO carryover rules")),
                "tools", List.of(Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "lookup_policy",
                                "description", "Look up policy snippets",
                                "parameters", Map.of("type", "object")))),
                "tool_choice", "auto");

        client().post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.tool_calls[0].type").isEqualTo("function")
                .jsonPath("$.choices[0].message.tool_calls[0].function.name").isEqualTo("lookup_policy")
                .jsonPath("$.choices[0].message.tool_calls[0].function.arguments")
                .value(arguments -> org.assertj.core.api.Assertions.assertThat((String) arguments)
                        .contains("PTO carryover"))
                .jsonPath("$.usage.totalTokens").value(total ->
                        org.assertj.core.api.Assertions.assertThat((Integer) total).isPositive());
    }
}
