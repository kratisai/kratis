package com.kratisai.controlplane;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Builder-based HTTP request body matcher that mirrors the {@link PromptMatcher} pattern. Used by
 * {@link MatcherBasedResponseTransformer} to select responses based on request body content.
 *
 * <p>Each matcher has a predicate condition on the request body, a response supplier, a content
 * type, and an optional max-matches limit. Thread-safe via {@link AtomicInteger} for the match
 * counter.
 */
public final class HttpRequestMatcher {

    private final List<Predicate<String>> conditions;
    private final Function<String, String> responseSupplier;
    private final String contentType;
    private final int maxMatches;
    private final AtomicInteger currentMatches;

    private HttpRequestMatcher(Builder builder) {
        this.conditions = List.copyOf(builder.conditions);
        this.responseSupplier = builder.responseSupplier;
        this.contentType = builder.contentType;
        this.maxMatches = builder.maxMatches;
        this.currentMatches = new AtomicInteger(0);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns {@code true} if this matcher has not been exhausted and all conditions match the given
     * request body.
     *
     * @param requestBody the HTTP request body string
     * @return whether this matcher matches
     */
    public boolean matches(String requestBody) {
        if (isExhausted()) {
            return false;
        }
        return conditions.stream().allMatch(c -> c.test(requestBody));
    }

    /**
     * Returns the response body if this matcher matches the given request body, or {@code null} if
     * it does not match. Increments the internal match counter on a successful match.
     *
     * @param requestBody the HTTP request body string
     * @return the response body string, or {@code null} if no match
     */
    public String getResponse(String requestBody) {
        if (matches(requestBody)) {
            currentMatches.incrementAndGet();
            return responseSupplier.apply(requestBody);
        }
        return null;
    }

    /**
     * Returns {@code true} if this matcher has reached its maximum number of matches.
     *
     * @return whether this matcher is exhausted
     */
    public boolean isExhausted() {
        return currentMatches.get() >= maxMatches;
    }

    /** Resets the match counter to zero. */
    public void reset() {
        currentMatches.set(0);
    }

    /** Returns the content type for the response produced by this matcher. */
    public String getContentType() {
        return contentType;
    }

    /** Returns the current number of times this matcher has been matched. */
    public int getMatchCount() {
        return currentMatches.get();
    }

    public static class Builder {
        private final List<Predicate<String>> conditions = new ArrayList<>();
        private Function<String, String> responseSupplier = body -> "{\"error\": \"no response configured\"}";
        private String contentType = "application/json";
        private int maxMatches = Integer.MAX_VALUE;

        /**
         * Adds a condition that the request body must contain the given text (case-sensitive).
         *
         * @param text the text to search for
         * @return this builder
         */
        public Builder contains(String text) {
            conditions.add(body -> body.contains(text));
            return this;
        }

        /**
         * Adds a condition that the request body must NOT contain the given text (case-sensitive).
         *
         * @param text the text that must not be present
         * @return this builder
         */
        public Builder notContains(String text) {
            conditions.add(body -> !body.contains(text));
            return this;
        }

        /**
         * Adds a condition that the request body must match the given regular expression
         * (case-insensitive).
         *
         * @param regex the regex pattern
         * @return this builder
         */
        public Builder regex(String regex) {
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            conditions.add(body -> pattern.matcher(body).find());
            return this;
        }

        /**
         * Adds a custom predicate condition on the request body.
         *
         * @param predicate the predicate
         * @return this builder
         */
        public Builder condition(Predicate<String> predicate) {
            conditions.add(predicate);
            return this;
        }

        /**
         * Sets a static JSON response body with content type {@code application/json}.
         *
         * @param json the JSON response body
         * @return this builder
         */
        public Builder jsonResponse(String json) {
            this.responseSupplier = body -> json;
            this.contentType = "application/json";
            return this;
        }

        /**
         * Sets a static SSE response body with content type {@code text/event-stream}.
         *
         * @param sse the SSE response body
         * @return this builder
         */
        public Builder sseResponse(String sse) {
            this.responseSupplier = body -> sse;
            this.contentType = "text/event-stream";
            return this;
        }

        /**
         * Sets a dynamic response supplier that receives the request body and returns the response
         * body. The content type defaults to {@code application/json} unless explicitly set via
         * {@link #jsonResponse(String)} or {@link #sseResponse(String)} before or after this call.
         *
         * @param supplier the response supplier function
         * @return this builder
         */
        public Builder response(Function<String, String> supplier) {
            this.responseSupplier = supplier;
            return this;
        }

        /**
         * Sets the content type for the response. Overrides the content type set by {@link
         * #jsonResponse(String)} or {@link #sseResponse(String)}.
         *
         * @param contentType the MIME content type
         * @return this builder
         */
        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        /**
         * Sets the maximum number of times this matcher can fire. After this many matches, {@link
         * #isExhausted()} returns {@code true} and {@link #matches(String)} returns {@code false}.
         *
         * @param max the maximum number of matches
         * @return this builder
         */
        public Builder maxMatches(int max) {
            this.maxMatches = max;
            return this;
        }

        public HttpRequestMatcher build() {
            return new HttpRequestMatcher(this);
        }
    }
}
