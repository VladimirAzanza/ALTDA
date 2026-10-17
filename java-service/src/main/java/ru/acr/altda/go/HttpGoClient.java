package ru.acr.altda.go;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.acr.altda.config.GoClientProperties;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HTTP implementation of {@link GoClient}. Sync calls with per-endpoint read timeouts.
 */
@Component
@ConditionalOnProperty(name = "altda.go.client", havingValue = "http")
public class HttpGoClient implements GoClient {

    private final ObjectMapper objectMapper;
    private final RestClient healthClient;
    private final RestClient parseClient;
    private final RestClient skeletonClient;
    private final RestClient regenerateClient;
    private final RestClient contentClient;
    private final RestClient validateClient;
    private final RestClient allocateClient;

    public HttpGoClient(ObjectMapper objectMapper, GoClientProperties properties) {
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        String baseUrl = properties.getBaseUrl();
        GoClientProperties.Timeouts t = properties.getTimeouts();
        this.healthClient = buildClient(httpClient, baseUrl, Math.min(t.getValidateMs(), 10_000));
        this.parseClient = buildClient(httpClient, baseUrl, t.getParseMs());
        this.skeletonClient = buildClient(httpClient, baseUrl, t.getSkeletonMs());
        this.regenerateClient = buildClient(httpClient, baseUrl, t.getRegenerateMs());
        this.contentClient = buildClient(httpClient, baseUrl, t.getContentMs());
        this.validateClient = buildClient(httpClient, baseUrl, t.getValidateMs());
        this.allocateClient = buildClient(httpClient, baseUrl, t.getAllocateMs());
    }

    private static RestClient buildClient(HttpClient httpClient, String baseUrl, long readTimeoutMs) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public HealthResult health() {
        JsonNode response = get(healthClient, "/v1/health");
        return new HealthResult(response.path("status").asText("ok"));
    }

    @Override
    public ParseResult parse(String rawText, JsonNode clarifications) {
        ObjectNode body = baseBody();
        body.put("rawText", rawText);
        body.putArray("attachments");
        body.set("clarifications", clarifications == null
                ? objectMapper.createArrayNode()
                : clarifications);
        JsonNode response = post(parseClient, "/v1/parse", body);
        return new ParseResult(
                response.get("params"),
                response.get("missing"),
                response.get("clarifications"),
                response.get("gapsReport")
        );
    }

    @Override
    public SkeletonGenerateResult generateSkeleton(ObjectNode program) {
        ObjectNode body = baseBody();
        body.set("program", program);
        body.putNull("selectedVariantKey");
        JsonNode response = post(skeletonClient, "/v1/skeleton/generate", body);
        return new SkeletonGenerateResult(response.get("variants"));
    }

    @Override
    public SkeletonRegenerateResult regenerateSkeleton(
            ObjectNode program,
            String comment,
            JsonNode nodeIds,
            boolean respectLocks
    ) {
        ObjectNode body = baseBody();
        body.set("program", program);
        body.put("comment", comment == null ? "" : comment);
        body.set("nodeIds", nodeIds == null ? objectMapper.nullNode() : nodeIds);
        body.put("respectLocks", respectLocks);
        JsonNode response = post(regenerateClient, "/v1/skeleton/regenerate", body);
        ObjectNode prog = response.has("program") && !response.get("program").isNull()
                ? (ObjectNode) response.get("program")
                : null;
        return new SkeletonRegenerateResult(prog, response.get("variants"), response.get("changedPaths"));
    }

    @Override
    public AllocateResult allocate(ObjectNode program) {
        ObjectNode body = baseBody();
        body.set("program", program);
        JsonNode response = post(allocateClient, "/v1/allocate", body);
        return new AllocateResult((ObjectNode) response.get("program"));
    }

    @Override
    public ContentGenerateResult generateContent(ObjectNode program, JsonNode nodeIds) {
        ObjectNode body = baseBody();
        body.set("program", program);
        body.set("nodeIds", nodeIds == null ? objectMapper.nullNode() : nodeIds);
        JsonNode response = post(contentClient, "/v1/content", body);
        JsonNode issues = response.has("issues") ? response.get("issues") : objectMapper.createArrayNode();
        return new ContentGenerateResult((ObjectNode) response.get("program"), issues);
    }

    @Override
    public ValidateResult validate(ObjectNode program) {
        ObjectNode body = baseBody();
        body.set("program", program);
        JsonNode response = post(validateClient, "/v1/validate", body);
        return new ValidateResult(response.get("validationReport"), response.get("issues"));
    }

    private ObjectNode baseBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("schemaVersion", SCHEMA_VERSION);
        return body;
    }

    private JsonNode get(RestClient client, String path) {
        try {
            return client.get()
                    .uri(path)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            throw toGoException(path, ex);
        } catch (Exception ex) {
            throw transportFailure(path, ex);
        }
    }

    private JsonNode post(RestClient client, String path, ObjectNode body) {
        try {
            return client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            throw toGoException(path, ex);
        } catch (Exception ex) {
            throw transportFailure(path, ex);
        }
    }

    /**
     * Maps Go {@code { "error", "message" }} bodies to {@link GoClientException}
     * with the contract error code and canonical HTTP status for the UI.
     */
    private GoClientException toGoException(String path, RestClientResponseException ex) {
        String code = GoClientException.LLM_ERROR;
        String message = "Go error on " + path;
        String raw = ex.getResponseBodyAsString();
        if (raw != null && !raw.isBlank()) {
            message = raw;
            try {
                JsonNode errBody = objectMapper.readTree(raw);
                if (errBody.hasNonNull("error")) {
                    code = errBody.get("error").asText();
                }
                if (errBody.hasNonNull("message")) {
                    message = errBody.get("message").asText();
                }
            } catch (Exception ignored) {
                // keep raw body as message
            }
        }
        return new GoClientException(code, message, GoClientException.canonicalHttpStatus(code), ex);
    }

    private static GoClientException transportFailure(String path, Exception ex) {
        String code = GoClientException.LLM_ERROR;
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (msg.contains("timeout") || msg.contains("timed out") || ex instanceof java.net.http.HttpTimeoutException
                || ex instanceof java.net.SocketTimeoutException) {
            code = GoClientException.LLM_TIMEOUT;
        }
        return new GoClientException(
                code,
                "Go service call failed on " + path + ": " + ex.getMessage(),
                GoClientException.canonicalHttpStatus(code),
                ex);
    }
}
