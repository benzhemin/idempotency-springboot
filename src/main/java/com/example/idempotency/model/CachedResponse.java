package com.example.idempotency.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CachedResponse {

    private final int statusCode;
    private final String body;
    private final String bodyHash;

    @JsonCreator
    public CachedResponse(
            @JsonProperty("statusCode") int statusCode,
            @JsonProperty("body") String body,
            @JsonProperty("bodyHash") String bodyHash) {
        this.statusCode = statusCode;
        this.body = body;
        this.bodyHash = bodyHash;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
    public String getBodyHash() { return bodyHash; }
}
