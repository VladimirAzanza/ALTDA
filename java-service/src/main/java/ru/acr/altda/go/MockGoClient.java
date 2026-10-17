package ru.acr.altda.go;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default stub ({@code altda.go.client=mock}): returns data built from
 * {@code examples/example_*.json}, adapting hours/type from the request text
 * so smoke tests can check hour totals without the real Go service.
 */
@Component
@ConditionalOnProperty(name = "altda.go.client", havingValue = "mock", matchIfMissing = true)
public class MockGoClient implements GoClient {

    private static final Pattern HOURS_PATTERN = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(?:ч|час)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final ObjectMapper objectMapper;

    private JsonNode skeletonResponse;
    private ObjectNode dppSkeleton;
    private ObjectNode fullProgram;

    public MockGoClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadExamples() throws IOException {
        skeletonResponse = readTree("examples/example_skeleton_response.json");
        dppSkeleton = (ObjectNode) readTree("examples/example_dpp_skeleton.json");
        fullProgram = (ObjectNode) readTree("examples/example_program.json");
    }

    private JsonNode readTree(String classpath) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpath);
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readTree(in);
        }
    }

    @Override
    public ParseResult parse(String rawText, JsonNode clarifications) {
        boolean answered = hasAnsweredClarifications(clarifications);
        boolean doop = isDoop(rawText);
        double hours = extractHours(rawText, doop ? 24 : 16);
        String title = doop
                ? guessTitle(rawText, "Общеразвивающая программа")
                : guessTitle(rawText, "ДПП повышения квалификации");

        ObjectNode params = objectMapper.createObjectNode();
        params.put("programType", doop ? "DOOP_GENERAL" : "DPP_PK");
        params.put("title", title);
        params.put("domain", doop ? "Искусственный интеллект" : "Анализ данных");
        params.put("totalHours", hours);
        params.put("audience", doop ? "Школьники / слушатели" : "Специалисты");
        params.put("studyForm", "очная");
        params.put("distanceTech", false);
        params.put("durationWeeks", Math.max(1, (int) Math.ceil(hours / 16.0)));
        params.put("sessionHours", 4);
        params.put("sessionsPerWeek", 2);
        params.put("minPracticeShare", 0.5);
        params.put("finalAttestationForm", "защита проекта");
        params.put("isInternship", false);
        params.putArray("deliverablesRequested");
        params.putArray("constraints");

        ArrayNode missing = objectMapper.createArrayNode();
        ArrayNode outClarifications = objectMapper.createArrayNode();
        ArrayNode gaps = objectMapper.createArrayNode();

        if (!answered) {
            params.putNull("entryLevel");
            missing.add("entryLevel");
            ObjectNode q = objectMapper.createObjectNode();
            q.put("param", "entryLevel");
            q.put("question", "Какой входной уровень слушателей?");
            q.putNull("answer");
            outClarifications.add(q);
        } else {
            String answer = firstAnswer(clarifications);
            params.put("entryLevel", answer != null ? answer : "Базовый");
            ObjectNode q = objectMapper.createObjectNode();
            q.put("param", "entryLevel");
            q.put("question", "Какой входной уровень слушателей?");
            q.put("answer", params.get("entryLevel").asText());
            outClarifications.add(q);
        }

        return new ParseResult(params, missing, outClarifications, gaps);
    }

    @Override
    public SkeletonGenerateResult generateSkeleton(ObjectNode program) {
        return new SkeletonGenerateResult(skeletonResponse.deepCopy());
    }

    @Override
    public SkeletonRegenerateResult regenerateSkeleton(ObjectNode program, String comment, JsonNode nodeIds) {
        ObjectNode updated = dppSkeleton.deepCopy();
        updated.put("id", program.path("id").asText());
        updated.put("status", "skeleton_proposed");
        if (program.has("request")) {
            updated.set("request", program.get("request").deepCopy());
        }
        if (comment != null && !comment.isBlank()) {
            ArrayNode gaps = updated.withObject("/request").withArray("gapsReport");
            gaps.removeAll();
            gaps.add("Revision requested: " + comment);
        }
        ArrayNode changed = objectMapper.createArrayNode();
        changed.add("/goal");
        changed.add("/curriculum");
        return new SkeletonRegenerateResult(updated, skeletonResponse.deepCopy(), changed);
    }

    @Override
    public ContentGenerateResult generateContent(ObjectNode program, JsonNode nodeIds) {
        ObjectNode content = fullProgram.deepCopy();
        content.put("id", program.path("id").asText());
        content.put("status", "content_ready");

        if (program.has("request")) {
            content.set("request", program.get("request").deepCopy());
        }

        String programType = program.path("programType").asText(
                program.path("request").path("params").path("programType").asText("DOOP_GENERAL"));
        content.put("programType", programType);

        double totalHours = program.path("header").path("totalHours").asDouble(
                program.path("request").path("params").path("totalHours").asDouble(16));

        if (program.has("header")) {
            content.set("header", program.get("header").deepCopy());
        } else {
            content.withObject("/header").put("totalHours", totalHours);
        }
        content.withObject("/header").put("totalHours", totalHours);

        if (program.has("goal")) {
            content.set("goal", program.get("goal").deepCopy());
        }
        if (program.has("competencies")) {
            content.set("competencies", program.get("competencies").deepCopy());
        }

        applyConsistentHours(content, totalHours);

        if ("DPP_PK".equals(programType)) {
            content.remove("doop");
            if (!content.has("dpp")) {
                ObjectNode dpp = content.putObject("dpp");
                ArrayNode standards = dpp.putArray("profStandards");
                ObjectNode ps = standards.addObject();
                ps.put("registryRef", "ps.06.001");
                ps.put("standardCode", "06.001");
                ps.put("standardTitle", "Программист");
                ps.putNull("approvalOrder");
                ps.put("verified", false);
                ps.putArray("laborFunctions")
                        .addObject()
                        .put("code", "A/01.3")
                        .put("title", "EXAMPLE ONLY");
            }
        } else {
            content.remove("dpp");
            if (!content.has("doop")) {
                content.putObject("doop").put("focus", "техническая");
            }
        }

        ArrayNode changed = objectMapper.createArrayNode();
        changed.add("/lectures");
        changed.add("/assessment");
        changed.add("/calendar");
        changed.add("/sources");
        changed.add("/curriculum");
        return new ContentGenerateResult(content, changed);
    }

    @Override
    public ValidateResult validate(ObjectNode program) {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("generatedAt", Instant.now().toString());
        report.put("iterations", 0);

        double requested = program.path("header").path("totalHours").asDouble(0);
        double totals = program.path("curriculum").path("totals").path("total").asDouble(0);
        boolean hoursOk = Math.abs(requested - totals) < 0.01;

        ObjectNode summary = report.putObject("summary");
        summary.put("errors", hoursOk ? 0 : 1);
        summary.put("warnings", 1);
        summary.put("passed", hoursOk ? 12 : 11);
        summary.put("unverifiedSources", 1);

        ArrayNode checks = report.putArray("checks");
        ObjectNode hoursCheck = checks.addObject();
        hoursCheck.put("ruleId", "HOURS_EQ_REQUESTED");
        hoursCheck.put("category", "hours");
        hoursCheck.put("severity", "error");
        hoursCheck.put("passed", hoursOk);
        hoursCheck.put("message", hoursOk
                ? "Sum equals requested hours"
                : "Sum " + totals + " h != requested " + requested + " h");
        hoursCheck.put("path", "/header/totalHours");

        ObjectNode warn = checks.addObject();
        warn.put("ruleId", "SOURCE_UNVERIFIED");
        warn.put("category", "sources");
        warn.put("severity", "warning");
        warn.put("passed", false);
        warn.put("message", "Source not verified in local registry");
        warn.put("path", "/sources/0");

        ArrayNode issues = objectMapper.createArrayNode();
        if (!hoursOk) {
            ObjectNode issue = issues.addObject();
            issue.put("ruleId", "HOURS_EQ_REQUESTED");
            issue.put("severity", "error");
            issue.put("message", hoursCheck.get("message").asText());
            issue.put("path", "/header/totalHours");
        }
        ObjectNode warnIssue = issues.addObject();
        warnIssue.put("ruleId", "SOURCE_UNVERIFIED");
        warnIssue.put("severity", "warning");
        warnIssue.put("message", "Source not verified in local registry");
        warnIssue.put("path", "/sources/0");

        return new ValidateResult(report, issues);
    }

    /**
     * Rebuild curriculum hours so module topic sums + final attestation == totalHours.
     */
    void applyConsistentHours(ObjectNode program, double totalHours) {
        double attestation = Math.min(4.0, Math.max(1.0, Math.round(totalHours * 0.1 * 2) / 2.0));
        double remainder = totalHours - attestation;
        if (remainder < 1) {
            attestation = 1;
            remainder = totalHours - attestation;
        }

        ObjectNode curriculum = program.withObject("/curriculum");
        ArrayNode modules = curriculum.withArray("modules");
        if (modules.isEmpty()) {
            ObjectNode m = modules.addObject();
            m.put("id", "M1");
            m.put("title", "Модуль 1");
            m.put("weight", 1);
            m.put("assessmentForm", "нет");
            m.putObject("hours");
            ArrayNode topics = m.putArray("topics");
            ObjectNode t = topics.addObject();
            t.put("id", "T1.1");
            t.put("title", "Тема 1.1");
            t.put("weight", 1);
            t.putObject("hours");
            t.putArray("outcomeIds");
        }

        int moduleCount = modules.size();
        double perModule = Math.round((remainder / moduleCount) * 2) / 2.0;
        double assigned = 0;

        for (int i = 0; i < moduleCount; i++) {
            ObjectNode mod = (ObjectNode) modules.get(i);
            double modHours = (i == moduleCount - 1) ? (remainder - assigned) : perModule;
            assigned += (i == moduleCount - 1) ? 0 : perModule;
            ArrayNode topics = mod.withArray("topics");
            if (topics.isEmpty()) {
                ObjectNode t = topics.addObject();
                t.put("id", "T" + (i + 1) + ".1");
                t.put("title", "Тема");
                t.put("weight", 1);
                t.putObject("hours");
                t.putArray("outcomeIds");
            }
            int topicCount = topics.size();
            double topicAssigned = 0;
            double modLecture = 0;
            double modPractice = 0;
            for (int ti = 0; ti < topicCount; ti++) {
                ObjectNode topic = (ObjectNode) topics.get(ti);
                double share = Math.round((modHours / topicCount) * 2) / 2.0;
                double th = (ti == topicCount - 1) ? (modHours - topicAssigned) : share;
                if (ti != topicCount - 1) {
                    topicAssigned += share;
                }
                double tPractice = Math.round(th * 0.6 * 2) / 2.0;
                double tLecture = th - tPractice;
                modLecture += tLecture;
                modPractice += tPractice;
                putHours(topic.putObject("hours"), tLecture, tPractice, 0, 0, 0);
                ArrayNode acts = topic.putArray("activities");
                if (tLecture > 0) {
                    acts.addObject()
                            .put("kind", "lecture")
                            .put("hours", tLecture)
                            .put("content", "Лекционный материал по теме");
                }
                if (tPractice > 0) {
                    acts.addObject()
                            .put("kind", "practice")
                            .put("hours", tPractice)
                            .put("content", "Практические задания по теме");
                }
            }
            putHours(mod.putObject("hours"), modLecture, modPractice, 0, 0, 0);
        }

        ObjectNode fa = program.withObject("/finalAttestation");
        putHours(fa.putObject("hours"), 0, 0, 0, 0, attestation);

        double lectureSum = 0;
        double practiceSum = 0;
        double labSum = 0;
        double selfSum = 0;
        double assessSum = attestation;
        double contactSum = 0;
        for (JsonNode mod : modules) {
            JsonNode h = mod.path("hours");
            lectureSum += h.path("lecture").asDouble();
            practiceSum += h.path("practice").asDouble();
            labSum += h.path("lab").asDouble();
            selfSum += h.path("selfStudy").asDouble();
            assessSum += h.path("assessment").asDouble();
            contactSum += h.path("contact").asDouble();
        }
        ObjectNode totals = curriculum.putObject("totals");
        totals.put("lecture", lectureSum);
        totals.put("practice", practiceSum);
        totals.put("lab", labSum);
        totals.put("selfStudy", selfSum);
        totals.put("assessment", assessSum);
        totals.put("contact", contactSum);
        totals.put("total", lectureSum + practiceSum + labSum + selfSum + assessSum);
    }

    private static void putHours(
            ObjectNode hours,
            double lecture,
            double practice,
            double lab,
            double selfStudy,
            double assessment
    ) {
        double contact = lecture + practice + lab;
        hours.put("lecture", lecture);
        hours.put("practice", practice);
        hours.put("lab", lab);
        hours.put("selfStudy", selfStudy);
        hours.put("assessment", assessment);
        hours.put("contact", contact);
        hours.put("total", contact + selfStudy + assessment);
    }

    static double extractHours(String rawText, double fallback) {
        if (rawText == null) {
            return fallback;
        }
        Matcher m = HOURS_PATTERN.matcher(rawText);
        if (m.find()) {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        }
        return fallback;
    }

    static boolean isDoop(String rawText) {
        if (rawText == null) {
            return false;
        }
        String lower = rawText.toLowerCase();
        return lower.contains("дооп")
                || lower.contains("общеразвива")
                || lower.contains("doop");
    }

    private static String guessTitle(String rawText, String fallback) {
        if (rawText == null || rawText.isBlank()) {
            return fallback;
        }
        String cleaned = rawText.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 80) {
            return cleaned.substring(0, 77) + "...";
        }
        return cleaned;
    }

    public JsonNode exampleSkeletonResponse() {
        return skeletonResponse.deepCopy();
    }

    public ObjectNode exampleDppSkeleton() {
        return dppSkeleton.deepCopy();
    }

    public ObjectNode exampleFullProgram() {
        return fullProgram.deepCopy();
    }

    private static boolean hasAnsweredClarifications(JsonNode clarifications) {
        if (clarifications == null || !clarifications.isArray() || clarifications.isEmpty()) {
            return false;
        }
        for (JsonNode c : clarifications) {
            JsonNode answer = c.get("answer");
            if (answer != null && !answer.isNull() && !answer.asText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String firstAnswer(JsonNode clarifications) {
        if (clarifications == null || !clarifications.isArray()) {
            return null;
        }
        for (JsonNode c : clarifications) {
            JsonNode answer = c.get("answer");
            if (answer != null && !answer.isNull() && !answer.asText().isBlank()) {
                return answer.asText();
            }
        }
        return null;
    }
}
