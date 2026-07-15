package com.example.gateway.web;

import java.util.List;

import com.example.gateway.api.GatewayPipeline;
import com.example.gateway.api.PipelineMode;
import com.example.gateway.auth.ApiKeyAuthFilter;
import com.example.gateway.config.GatewayPipelineConfig;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

/**
 * Slice test for {@code /v1/chat/completions}: the controller wired to the PASSTHROUGH
 * {@link GatewayPipeline} from {@link GatewayPipelineConfig}, with a stub filter standing
 * in for {@link ApiKeyAuthFilter} to publish the resolved tenant. Verifies a 200 with a
 * populated {@link com.example.gateway.observability.RequestRecord} projection, plus the
 * missing-tenant and malformed-body error paths. No database, Redis, or live provider.
 */
class ChatCompletionControllerTest {

    private final GatewayPipeline pipeline = new GatewayPipelineConfig().gatewayPipeline();

    /** Stand-in for the auth filter: publishes the resolved tenant as an exchange attribute. */
    private final WebFilter tenantFilter = (exchange, chain) -> {
        exchange.getAttributes().put(ApiKeyAuthFilter.TENANT_ID_ATTRIBUTE, "tenant-1");
        return chain.filter(exchange);
    };

    private WebTestClient authenticatedClient() {
        return WebTestClient.bindToController(new ChatCompletionController(pipeline))
                .webFilter(tenantFilter)
                .build();
    }

    private WebTestClient unauthenticatedClient() {
        return WebTestClient.bindToController(new ChatCompletionController(pipeline)).build();
    }

    private ChatCompletionRequest validBody() {
        return new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message("user", "Hello gateway")), 64, false);
    }

    @Test
    void passthroughReturns200AndRequestRecordProjection() {
        authenticatedClient().post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Gateway-Mode", PipelineMode.PASSTHROUGH.name())
                .bodyValue(validBody())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenant").isEqualTo("tenant-1")
                .jsonPath("$.model").isEqualTo("gpt-4o")
                .jsonPath("$.mode").isEqualTo(PipelineMode.PASSTHROUGH.name())
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.object").isEqualTo("chat.completion")
                .jsonPath("$.cacheType").isEqualTo("NONE")
                .jsonPath("$.usage.promptTokens").isEqualTo(4)
                .jsonPath("$.usage.totalTokens").value(total -> {
                    org.assertj.core.api.Assertions.assertThat((Integer) total).isPositive();
                });
    }

    @Test
    void streamingRequestReturnsServerSentChunksAndDoneSentinel() {
        ChatCompletionRequest body = new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message("user", "Stream this response")), 64, true);

        authenticatedClient().post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(response -> {
                    org.assertj.core.api.Assertions.assertThat(response)
                            .contains("chat.completion.chunk")
                            .contains("\"role\":\"assistant\"")
                            .contains("\"finish_reason\":\"stop\"")
                            .contains("[DONE]");
                });
    }

    @Test
    void missingTenantReturns401() {
        unauthenticatedClient().post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void emptyMessagesReturns400() {
        ChatCompletionRequest body = new ChatCompletionRequest("gpt-4o", List.of(), 64, false);
        authenticatedClient().post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void blankModelReturns400() {
        ChatCompletionRequest body = new ChatCompletionRequest("",
                List.of(new ChatCompletionRequest.Message("user", "Hi")), 64, false);
        authenticatedClient().post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void unsupportedModeReturnsStable400Message() {
        authenticatedClient().post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Gateway-Mode", "not-a-mode")
                .bodyValue(validBody())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).isEqualTo("unsupported X-Gateway-Mode");
    }
}
