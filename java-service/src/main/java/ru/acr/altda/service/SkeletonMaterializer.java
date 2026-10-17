package ru.acr.altda.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Merges a chosen Go skeleton variant into the working program locally.
 * Hours must already be allocated on the variant by Go — Java never computes them.
 * There is no {@code /materialize} endpoint.
 */
@Component
public class SkeletonMaterializer {

    private final ObjectMapper objectMapper;

    public SkeletonMaterializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode mergeVariant(ObjectNode current, JsonNode variant) {
        ObjectNode result = current.deepCopy();
        result.put("schemaVersion", "1.1");
        result.put("status", "skeleton_approved");

        JsonNode params = current.path("request").path("params");
        if (!params.isMissingNode() && params.hasNonNull("programType")) {
            result.put("programType", params.get("programType").asText());
        }

        ObjectNode header = result.putObject("header");
        header.put("title", textOr(params, "title", variant.path("key").asText("Program")));
        header.put("itDirection", textOr(params, "domain", "IT"));
        if (params.has("totalHours") && !params.get("totalHours").isNull()) {
            header.put("totalHours", params.get("totalHours").asDouble());
        } else if (variant.has("totals") && variant.path("totals").has("total")) {
            header.put("totalHours", variant.path("totals").path("total").asDouble());
        } else {
            header.put("totalHours", 16);
        }
        header.put("studyForm", textOr(params, "studyForm", "очная"));
        header.put("distanceLearning", params.path("distanceTech").asBoolean(false));
        if (params.has("durationWeeks") && !params.get("durationWeeks").isNull()) {
            header.put("durationText", params.get("durationWeeks").asInt() + " недель");
        }
        ObjectNode audience = header.putObject("audience");
        audience.put("description", textOr(params, "audience", ""));
        audience.put("entryLevel", textOr(params, "entryLevel", ""));
        String programType = result.path("programType").asText("");
        if ("DPP_PK".equals(programType)) {
            header.put("issuedDocument", "Удостоверение о повышении квалификации");
            audience.put("entryRequirements", "Среднее профессиональное или высшее образование");
        } else {
            header.put("issuedDocument", "Сертификат об обучении");
        }
        ObjectNode approval = header.putObject("approval");
        approval.putNull("orderNumber");
        approval.putNull("orderDate");
        approval.putNull("approvedBy");
        ArrayNode authors = header.putArray("authors");
        authors.addObject().put("role", "Методист").putNull("fullName");
        authors.addObject().put("role", "Эксперт-практик").putNull("fullName");

        ObjectNode goal = result.putObject("goal");
        if (variant.has("goal") && variant.get("goal").isTextual()) {
            goal.put("text", variant.get("goal").asText());
        } else if (variant.has("goal") && variant.get("goal").isObject()) {
            goal.setAll((ObjectNode) variant.get("goal"));
        } else {
            goal.put("text", "Цель программы");
        }
        if (!goal.has("tasks")) {
            goal.putArray("tasks");
        }

        if (variant.has("competencies")) {
            result.set("competencies", variant.get("competencies").deepCopy());
        }

        ObjectNode curriculum = result.putObject("curriculum");
        ArrayNode modules = curriculum.putArray("modules");
        if (variant.has("modules") && variant.get("modules").isArray()) {
            for (JsonNode mod : variant.get("modules")) {
                ObjectNode copy = mod.deepCopy();
                if (!copy.has("assessmentForm")) {
                    copy.put("assessmentForm", "нет");
                }
                // Hours must already be present from Go — do not invent or zero them
                modules.add(copy);
            }
        }
        if (variant.has("totals")) {
            curriculum.set("totals", variant.get("totals").deepCopy());
        } else {
            curriculum.set("totals", sumModuleHours(modules));
        }

        ObjectNode finalAttestation = result.putObject("finalAttestation");
        if (variant.has("finalAttestation")) {
            JsonNode fa = variant.get("finalAttestation");
            finalAttestation.put("form", fa.path("form").asText("зачёт"));
            finalAttestation.put("description", fa.path("description").asText(""));
            if (fa.has("outcomeIds")) {
                finalAttestation.set("outcomeIds", fa.get("outcomeIds").deepCopy());
            } else {
                finalAttestation.putArray("outcomeIds");
            }
            if (fa.has("hours")) {
                finalAttestation.set("hours", fa.get("hours").deepCopy());
            }
        } else {
            finalAttestation.put("form", textOr(params, "finalAttestationForm", "зачёт"));
            finalAttestation.put("description", "");
            finalAttestation.putArray("outcomeIds");
        }

        if ("DPP_PK".equals(programType)) {
            ObjectNode dpp = result.putObject("dpp");
            ArrayNode standards = dpp.putArray("profStandards");
            ObjectNode ps = standards.addObject();
            ps.put("registryRef", "ps.06.001");
            ps.put("standardCode", "06.001");
            ps.put("standardTitle", "Программист");
            ps.putNull("approvalOrder");
            ps.put("verified", false);
            ArrayNode labor = ps.putArray("laborFunctions");
            labor.addObject().put("code", "A/01.3").put("title", "EXAMPLE ONLY");
            result.remove("doop");
        } else if ("DOOP_GENERAL".equals(programType)) {
            ObjectNode doop = result.putObject("doop");
            doop.put("focus", "техническая");
            result.remove("dpp");
        }

        return result;
    }

    /** @deprecated use {@link #mergeVariant} — kept for call-site clarity during rename */
    public ObjectNode materialize(ObjectNode current, JsonNode variant) {
        return mergeVariant(current, variant);
    }

    private ObjectNode sumModuleHours(ArrayNode modules) {
        double lecture = 0;
        double practice = 0;
        double lab = 0;
        double selfStudy = 0;
        double assessment = 0;
        double contact = 0;
        double total = 0;
        for (JsonNode m : modules) {
            JsonNode h = m.path("hours");
            lecture += h.path("lecture").asDouble(0);
            practice += h.path("practice").asDouble(0);
            lab += h.path("lab").asDouble(0);
            selfStudy += h.path("selfStudy").asDouble(0);
            assessment += h.path("assessment").asDouble(0);
            contact += h.path("contact").asDouble(0);
            total += h.path("total").asDouble(0);
        }
        ObjectNode totals = objectMapper.createObjectNode();
        totals.put("lecture", lecture);
        totals.put("practice", practice);
        totals.put("lab", lab);
        totals.put("selfStudy", selfStudy);
        totals.put("assessment", assessment);
        totals.put("contact", contact);
        totals.put("total", total);
        return totals;
    }

    private static String textOr(JsonNode params, String field, String fallback) {
        JsonNode n = params.get(field);
        if (n != null && !n.isNull() && n.isTextual() && !n.asText().isBlank()) {
            return n.asText();
        }
        return fallback;
    }
}
