package ru.acr.altda.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Merges a chosen skeleton variant into the working program (MVP: no Go round-trip).
 * Hours stay unset here when the variant only has weights; mock/Go content step fills them.
 */
@Component
public class SkeletonMaterializer {

    private final ObjectMapper objectMapper;

    public SkeletonMaterializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode materialize(ObjectNode current, JsonNode variant) {
        ObjectNode result = current.deepCopy();
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
                modules.add(mod.deepCopy());
            }
        }
        // Placeholder totals — content/allocate step replaces with real hours
        ObjectNode totals = curriculum.putObject("totals");
        zeroHours(totals);

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
        } else {
            finalAttestation.put("form", textOr(params, "finalAttestationForm", "зачёт"));
            finalAttestation.put("description", "");
            finalAttestation.putArray("outcomeIds");
        }
        ObjectNode faHours = finalAttestation.putObject("hours");
        zeroHours(faHours);

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

    private static String textOr(JsonNode params, String field, String fallback) {
        JsonNode n = params.get(field);
        if (n != null && !n.isNull() && n.isTextual() && !n.asText().isBlank()) {
            return n.asText();
        }
        return fallback;
    }

    private static void zeroHours(ObjectNode hours) {
        hours.put("lecture", 0);
        hours.put("practice", 0);
        hours.put("lab", 0);
        hours.put("selfStudy", 0);
        hours.put("assessment", 0);
        hours.put("contact", 0);
        hours.put("total", 0);
    }
}
