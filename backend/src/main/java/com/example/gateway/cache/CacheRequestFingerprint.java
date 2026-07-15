package com.example.gateway.cache;

import com.example.gateway.provider.CompletionRequest;
import com.example.gateway.provider.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Builds a deterministic cache partition for fields that can change a provider response. */
final class CacheRequestFingerprint {

    private CacheRequestFingerprint() {
    }

    static String responseContract(CompletionRequest request) {
        StringBuilder material = new StringBuilder();
        appendString(material, request.alias());
        material.append('I').append(request.maxTokens()).append(';');
        appendString(material, request.toolChoice());
        material.append('L').append(request.tools().size()).append('[');
        for (ToolDefinition tool : request.tools()) {
            appendString(material, tool.type());
            appendString(material, tool.function().name());
            appendString(material, tool.function().description());
            appendValue(material, tool.function().parameters());
        }
        material.append(']');
        return sha256(material.toString());
    }

    static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void appendValue(StringBuilder target, Object value) {
        if (value == null) {
            target.append('N').append(';');
        } else if (value instanceof String text) {
            appendString(target, text);
        } else if (value instanceof Number number) {
            target.append('D').append(number).append(';');
        } else if (value instanceof Boolean bool) {
            target.append('B').append(bool).append(';');
        } else if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort((left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey())));
            target.append('M').append(entries.size()).append('{');
            for (Map.Entry<?, ?> entry : entries) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("tool parameter keys must be strings");
                }
                appendString(target, key);
                appendValue(target, entry.getValue());
            }
            target.append('}');
        } else if (value instanceof List<?> list) {
            target.append('L').append(list.size()).append('[');
            for (Object item : list) {
                appendValue(target, item);
            }
            target.append(']');
        } else {
            throw new IllegalArgumentException(
                    "unsupported tool parameter value: " + value.getClass().getSimpleName());
        }
    }

    private static void appendString(StringBuilder target, String value) {
        if (value == null) {
            target.append('N').append(';');
            return;
        }
        target.append('S').append(value.length()).append(':').append(value).append(';');
    }
}
