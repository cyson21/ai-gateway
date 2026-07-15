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
        ChatCompletionRequest.Message third = new ChatCompletionRequest.Message(null, "assistant follow-up");

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
    void handlesNullContentAndNullRoleByApplyingFieldDefaults() {
        ChatCompletionRequest.Message nullRole = new ChatCompletionRequest.Message(null, "null role content");
        ChatCompletionRequest.Message nullContent = new ChatCompletionRequest.Message("assistant", null);

        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o",
                List.of(nullRole, nullContent),
                64,
                false);

        CompletionRequest completionRequest = request.toCompletionRequest("tenant-1");

        assertThat(completionRequest.prompt())
                .isEqualTo("user: null role content\nassistant: ");
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
        assertDefaultMaxTokensAndObservedDefaults(0);
        assertDefaultMaxTokensAndObservedDefaults(-1);
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
