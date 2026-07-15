package com.example.gateway.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.ToolDefinition;
import com.example.gateway.provider.ToolDefinition.FunctionDefinition;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatCompletionRequestTest {

    @Test
    void preservesMessageOrderAndInsertsSingleNewlineBetweenMessages() {
        ChatCompletionRequest.Message first = new ChatCompletionRequest.Message("system", "system prompt");
        ChatCompletionRequest.Message second = new ChatCompletionRequest.Message("user", "line1\nline2");
        ChatCompletionRequest.Message third = new ChatCompletionRequest.Message(" USER ", "assistant follow-up");

        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o",
                List.of(first, second, third),
                64,
                false);

        CompletionRequest completionRequest = request.toCompletionRequest("tenant-1");

        assertThat(completionRequest.prompt())
                .isEqualTo("system: system prompt\nuser: line1\nline2\nuser: assistant follow-up");
    }

    @Test
    void rejectsMissingRoleAndContentWithIndexedErrors() {
        assertThatThrownBy(() -> new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message(null, "content")), 64, false)
                .toCompletionRequest("tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("messages[0].role is required");
        assertThatThrownBy(() -> new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message("assistant", "  ")), 64, false)
                .toCompletionRequest("tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("messages[0].content is required");
    }

    @Test
    void rejectsMissingAndEmptyMessages() {
        ChatCompletionRequest emptyMessages = new ChatCompletionRequest("gpt-4o", List.of(), 64, false);

        assertThatThrownBy(() -> emptyMessages.toCompletionRequest("tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("messages is required");
        assertThatThrownBy(() -> new ChatCompletionRequest("gpt-4o", null, 64, false).toCompletionRequest("tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("messages is required");
    }

    @Test
    void rejectsNullElementInsteadOfSilentlyDroppingIt() {
        List<ChatCompletionRequest.Message> messages = new ArrayList<>();
        messages.add(new ChatCompletionRequest.Message("user", "valid"));
        messages.add(null);

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o", messages, 64, false);

        assertThatThrownBy(() -> request.toCompletionRequest("tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("messages must not contain null elements");
    }

    @Test
    void rejectsAllNullMessagesInsteadOfCreatingEmptyPrompt() {
        List<ChatCompletionRequest.Message> messages = new ArrayList<>();
        messages.add(null);
        messages.add(null);

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o", messages, 64, false);

        assertThatThrownBy(() -> request.toCompletionRequest("tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("messages must not contain null elements");
    }

    @Test
    void appliesDefaultMaxTokensAndStreamAndNullToolDefaults() {
        assertDefaultMaxTokensAndObservedDefaults(null);
    }

    @Test
    void rejectsNonPositiveAndExcessiveMaxTokens() {
        for (int invalid : List.of(0, -1)) {
            assertThatThrownBy(() -> new ChatCompletionRequest("gpt-4o",
                    List.of(new ChatCompletionRequest.Message("user", "ping")), invalid, false)
                    .toCompletionRequest("tenant-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("max_tokens must be positive");
        }
        assertThatThrownBy(() -> new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message("user", "ping")),
                ChatCompletionRequest.MAX_MAX_TOKENS + 1, false).toCompletionRequest("tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("max_tokens must be at most " + ChatCompletionRequest.MAX_MAX_TOKENS);
    }

    @Test
    void rejectsUnsupportedRole() {
        assertThatThrownBy(() -> new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message("owner", "ping")), 64, false)
                .toCompletionRequest("tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("messages[0].role is unsupported");
    }

    @Test
    void rejectsNullToolsAndInvalidToolChoiceBoundaries() {
        List<ToolDefinition> toolsWithNull = new ArrayList<>();
        toolsWithNull.add(null);
        assertThatThrownBy(() -> new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message("user", "ping")), 64, false,
                toolsWithNull, null).toCompletionRequest("tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tools must not contain null elements");
        assertThatThrownBy(() -> new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message("user", "ping")), 64, false,
                List.of(), "sometimes").toCompletionRequest("tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tool_choice is unsupported");
        assertThatThrownBy(() -> new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message("user", "ping")), 64, false,
                List.of(), "required").toCompletionRequest("tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tool_choice requires at least one tool");
    }

    private void assertDefaultMaxTokensAndObservedDefaults(Integer maxTokens) {
        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message("user", "ping")),
                maxTokens,
                null,
                null,
                null);

        CompletionRequest completionRequest = request.toCompletionRequest("tenant-1");

        assertThat(completionRequest.maxTokens()).isEqualTo(ChatCompletionRequest.DEFAULT_MAX_TOKENS);
        assertThat(completionRequest.stream()).isFalse();
        assertThat(completionRequest.tools()).isEmpty();
        assertThat(completionRequest.toolChoice()).isNull();
    }

    @Test
    void propagatesStreamFlag() {
        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message("user", "ping")),
                64,
                true);
        CompletionRequest completionRequest = request.toCompletionRequest("tenant-1");

        assertThat(completionRequest.stream()).isTrue();
    }

    @Test
    void propagatesToolsAndToolChoice() {
        List<ToolDefinition> tools = List.of(
                new ToolDefinition("function", new FunctionDefinition(
                        "lookup_policy",
                        "Look up leave policy",
                        Map.of("type", "object"))));

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o",
                List.of(new ChatCompletionRequest.Message("user", "Find PTO policy")),
                64,
                false,
                tools,
                "auto");
        CompletionRequest completionRequest = request.toCompletionRequest("tenant-1");

        assertThat(completionRequest.tools()).isEqualTo(tools);
        assertThat(completionRequest.toolChoice()).isEqualTo("auto");
    }
}
