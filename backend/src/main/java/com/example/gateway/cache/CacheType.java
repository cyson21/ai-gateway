package com.example.gateway.cache;

/** How a response was served from cache, recorded on every request. */
public enum CacheType {
    /** No cache involved; provider was called. */
    NONE,
    /** Served from an exact prompt-hash match. */
    EXACT,
    /** Served from a semantic (embedding similarity) match above threshold. */
    SEMANTIC
}
