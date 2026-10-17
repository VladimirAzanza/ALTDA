package ru.acr.altda.go;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sync contract with the Go LLM-skills service. See {@code docs/API.md}.
 * Java never computes hours; Go allocates them (skeleton generate / allocate / content).
 */
public interface GoClient {

    String SCHEMA_VERSION = "1.1";

    /** GET /v1/health */
    HealthResult health();

    ParseResult parse(String rawText, JsonNode clarifications);

    /** Variants already include allocated hours; Java merges the chosen one locally. */
    SkeletonGenerateResult generateSkeleton(ObjectNode program);

    /**
     * With {@code respectLocks=true}, targeting a locked nodeId yields {@link GoClientException}
     * with code {@code node_locked} (HTTP 409).
     */
    SkeletonRegenerateResult regenerateSkeleton(
            ObjectNode program,
            String comment,
            JsonNode nodeIds,
            boolean respectLocks
    );

    /** Rebalance hours after manual edits only. */
    AllocateResult allocate(ObjectNode program);

    /** POST /v1/content — returns program + issues[]. */
    ContentGenerateResult generateContent(ObjectNode program, JsonNode nodeIds);

    ValidateResult validate(ObjectNode program);

    record HealthResult(String status) {
    }

    record ParseResult(
            JsonNode params,
            JsonNode missing,
            JsonNode clarifications,
            JsonNode gapsReport
    ) {
    }

    record SkeletonGenerateResult(JsonNode variants) {
    }

    record SkeletonRegenerateResult(ObjectNode program, JsonNode variants, JsonNode changedPaths) {
    }

    record AllocateResult(ObjectNode program) {
    }

    record ContentGenerateResult(ObjectNode program, JsonNode issues) {
    }

    record ValidateResult(JsonNode validationReport, JsonNode issues) {
    }
}
