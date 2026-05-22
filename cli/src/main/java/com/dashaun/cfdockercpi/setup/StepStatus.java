package com.dashaun.cfdockercpi.setup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record StepStatus(Status status, String ranAt, String detail) {

    public enum Status { NEW, PASS, FAIL, SKIP }

    @JsonCreator
    public StepStatus(
            @JsonProperty("status") Status status,
            @JsonProperty("ranAt") String ranAt,
            @JsonProperty("detail") String detail) {
        this.status = status;
        this.ranAt = ranAt;
        this.detail = detail;
    }

    public static StepStatus pass(String detail) {
        return new StepStatus(Status.PASS, Instant.now().toString(), detail);
    }

    public static StepStatus fail(String detail) {
        return new StepStatus(Status.FAIL, Instant.now().toString(), detail);
    }

    public Instant ranAtInstant() {
        if (ranAt == null || ranAt.isBlank()) return null;
        try {
            return Instant.parse(ranAt);
        } catch (Exception e) {
            return null;
        }
    }
}
