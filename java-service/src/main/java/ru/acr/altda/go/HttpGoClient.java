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
 * HTTP implementation of {@link GoClient}. Each Go endpoint uses its own read timeout
 * from {@code altda.go.timeouts.*}.
 */
@Component
@ConditionalOnProperty(name = "altda.go.client", havingValue = "http")
public class HttpGoClient implements GoClient {

    private final ObjectMapper objectMapper;
    private final RestClient parseClient;
    private final RestClient skeletonClient;
    private final RestClient regenerateClient;
    private final RestClient contentClient;
    private final RestClient validateClient;

    public HttpGoClient(ObjectMapper objectMapper, GoClientProperties properties) {
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        String baseUrl = properties.getBaseUrl();
        GoClientProperties.Timeouts t = properties.getTimeouts();
        this.parseClient = buildClient(httpClient, baseUrl, t.getParseMs());
        this.skeletonClient = buildClient(httpClient, baseUrl, t.getSkeletonMs());
        this.regenerateClient = buildClient(httpClient, baseUrl, t.getRegenerateMs());
        this.contentClient = buildClient(httpClient, baseUrl, t.getContentMs());
        this.validateClient = buildClient(httpClient, baseUrl, t.getValidateMs());
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
    public ParseResult parse(String rawText, JsonNode clarifications) {
        ObjectNode body = objectMapper.createObjectNode();
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
        ObjectNode body = objectMapper.createObjectNode();
        body.set("program", program);
        body.putNull("selectedVariantKey");
        JsonNode response = post(skeletonClient, "/v1/skeleton/generate", body);
        return new SkeletonGenerateResult(response.get("variants"));
    }

    @Override
    public SkeletonRegenerateResult regenerateSkeleton(ObjectNode program, String comment, JsonNode nodeIds) {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("program", program);
        body.put("comment", comment == null ? "" : comment);
        body.set("nodeIds", nodeIds == null ? objectMapper.nullNode() : nodeIds);
        body.put("respectLocks", true);
        JsonNode response = post(regenerateClient, "/v1/skeleton/regenerate", body);
        ObjectNode prog = response.has("program") && !response.get("program").isNull()
                ? (ObjectNode) response.get("program")
                : null;
        return new SkeletonRegenerateResult(prog, response.get("variants"), response.get("changedPaths"));
    }

    @Override
    public ContentGenerateResult generateContent(ObjectNode program, JsonNode nodeIds) {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("program", program);
        body.set("nodeIds", nodeIds == null ? objectMapper.nullNode() : nodeIds);
        JsonNode response = post(contentClient, "/v1/content/generate", body);
        return new ContentGenerateResult((ObjectNode) response.get("program"), response.get("changedPaths"));
    }

    @Override
    public ValidateResult validate(ObjectNode program) {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("program", program);
        JsonNode response = post(validateClient, "/v1/validate", body);
        return new ValidateResult(response.get("validationReport"), response.get("issues"));
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
            throw new GoClientException(
                    "Go service error " + ex.getStatusCode().value() + " on " + path + ": "
                            + ex.getResponseBodyAsString(),
                    ex);
        } catch (Exception ex) {
            throw new GoClientException("Go service call failed on " + path + ": " + ex.getMessage(), ex);
        }
    }
}
