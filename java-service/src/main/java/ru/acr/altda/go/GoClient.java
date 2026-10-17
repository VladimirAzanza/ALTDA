package ru.acr.altda.go;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Contract with the Go LLM-skills service. See {@code docs/API.md}.
 */
public interface GoClient {

    ParseResult parse(String rawText, JsonNode clarifications);

    SkeletonGenerateResult generateSkeleton(ObjectNode program);

    SkeletonRegenerateResult regenerateSkeleton(ObjectNode program, String comment, JsonNode nodeIds);

    ContentGenerateResult generateContent(ObjectNode program, JsonNode nodeIds);

    ValidateResult validate(ObjectNode program);

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

    record ContentGenerateResult(ObjectNode program, JsonNode changedPaths) {
    }

    record ValidateResult(JsonNode validationReport, JsonNode issues) {
    }
}
