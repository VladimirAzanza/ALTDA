package ru.acr.altda.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import ru.acr.altda.api.ConflictException;
import ru.acr.altda.api.dto.ClarificationAnswerRequest;
import ru.acr.altda.api.dto.ContentApproveRequest;
import ru.acr.altda.api.dto.CreateProgramRequest;
import ru.acr.altda.api.dto.GenerateRequest;
import ru.acr.altda.api.dto.GenerateResponse;
import ru.acr.altda.api.dto.LockNodesRequest;
import ru.acr.altda.api.dto.RegenerateRequest;
import ru.acr.altda.api.dto.ReturnRequest;
import ru.acr.altda.api.dto.SkeletonApproveRequest;
import ru.acr.altda.domain.HistoryEntry;
import ru.acr.altda.domain.ProgramRecord;
import ru.acr.altda.domain.ProgramStatus;
import ru.acr.altda.domain.ProgramStatusMachine;
import ru.acr.altda.go.GoClient;
import ru.acr.altda.go.GoClientException;
import ru.acr.altda.store.ProgramStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProgramService {

    private final ProgramStore store;
    private final GoClient goClient;
    private final ObjectMapper objectMapper;
    private final ProgramAsyncJobs asyncJobs;
    private final SkeletonMaterializer skeletonMaterializer;
    private final ProgramServiceSupport support;

    public ProgramService(
            ProgramStore store,
            GoClient goClient,
            ObjectMapper objectMapper,
            ProgramAsyncJobs asyncJobs,
            SkeletonMaterializer skeletonMaterializer,
            ProgramServiceSupport support
    ) {
        this.store = store;
        this.goClient = goClient;
        this.objectMapper = objectMapper;
        this.asyncJobs = asyncJobs;
        this.skeletonMaterializer = skeletonMaterializer;
        this.support = support;
    }

    public ProgramRecord create(CreateProgramRequest request) {
        UUID id = UUID.randomUUID();
        ObjectNode program = objectMapper.createObjectNode();
        program.put("schemaVersion", "1.1");
        program.put("id", id.toString());
        program.put("status", ProgramStatus.INTAKE.jsonValue());
        ObjectNode req = program.putObject("request");
        req.put("rawText", request.rawText());
        req.putArray("attachments");
        req.putArray("missing");
        req.putArray("clarifications");
        req.putArray("gapsReport");

        ProgramRecord record = new ProgramRecord(id, program);
        store.saveNew(record);
        asyncJobs.runParseAndMaybeSkeleton(id);
        return store.require(id);
    }

    public List<ProgramRecord> list() {
        return store.findAll();
    }

    public ProgramRecord get(UUID id) {
        return store.require(id);
    }

    public ProgramRecord answerClarifications(UUID id, ClarificationAnswerRequest body) {
        ProgramRecord record = store.require(id);
        assertEditable(record);
        if (record.getStatus() != ProgramStatus.CLARIFYING
                && record.getStatus() != ProgramStatus.FAILED) {
            throw new IllegalStateException(
                    "Clarifications only accepted in clarifying/failed, was " + record.getStatus().jsonValue());
        }

        store.update(id, r -> {
            ObjectNode request = r.getProgram().withObject("/request");
            ArrayNode clarifications = request.withArray("clarifications");
            for (ClarificationAnswerRequest.Answer answer : body.answers()) {
                boolean found = false;
                for (JsonNode node : clarifications) {
                    if (answer.param().equals(node.path("param").asText())) {
                        ((ObjectNode) node).put("answer", answer.answer());
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    ObjectNode added = clarifications.addObject();
                    added.put("param", answer.param());
                    added.put("question", "");
                    added.put("answer", answer.answer());
                }
            }
            r.clearError();
            r.setBusy(true);
            r.addHistory(new HistoryEntry(
                    Instant.now(), "clarifications_submitted", r.getStatus(), r.getStatus(), null, null));
        });

        asyncJobs.runParseAndMaybeSkeleton(id);
        return store.require(id);
    }

    public ProgramRecord approveSkeleton(UUID id, SkeletonApproveRequest body) {
        ProgramRecord record = store.require(id);
        assertEditable(record);
        if (record.getStatus() != ProgramStatus.SKELETON_PROPOSED) {
            throw new IllegalStateException(
                    "Skeleton approve requires skeleton_proposed, was " + record.getStatus().jsonValue());
        }
        if (record.getSkeletonVariants() == null) {
            throw new IllegalStateException("No skeleton variants available");
        }

        JsonNode chosen = findVariant(record.getSkeletonVariants(), body.variantKey());
        if (chosen == null) {
            throw new IllegalArgumentException("Unknown variantKey: " + body.variantKey());
        }

        store.update(id, r -> {
            ProgramStatus from = r.getStatus();
            ProgramStatusMachine.assertTransition(from, ProgramStatus.SKELETON_APPROVED);
            ObjectNode materialized = skeletonMaterializer.mergeVariant(r.getProgram(), chosen);
            materialized.put("status", ProgramStatus.SKELETON_APPROVED.jsonValue());
            r.setProgram(materialized);
            r.setSelectedVariantKey(body.variantKey());
            r.clearError();
            r.setStatus(ProgramStatus.SKELETON_APPROVED);
            appendApproval(r.getProgram(), "skeleton", true, body.comment());
            r.addHistory(new HistoryEntry(
                    Instant.now(), "skeleton_approved", from, ProgramStatus.SKELETON_APPROVED,
                    body.comment(), body.variantKey()));
            r.setBusy(true);
        });

        asyncJobs.runContentAndValidate(id);
        return store.require(id);
    }

    public ProgramRecord returnSkeleton(UUID id, ReturnRequest body) {
        ProgramRecord record = store.require(id);
        assertEditable(record);
        if (record.getStatus() != ProgramStatus.SKELETON_PROPOSED
                && record.getStatus() != ProgramStatus.FAILED) {
            throw new IllegalStateException(
                    "Skeleton return requires skeleton_proposed/failed, was " + record.getStatus().jsonValue());
        }

        store.update(id, r -> {
            ProgramStatus from = r.getStatus();
            ProgramStatusMachine.assertTransition(from, ProgramStatus.SKELETON_PROPOSED);
            appendApproval(r.getProgram(), "skeleton", false, body.comment());
            r.clearError();
            r.setStatus(ProgramStatus.SKELETON_PROPOSED);
            r.setBusy(true);
            r.addHistory(new HistoryEntry(
                    Instant.now(), "skeleton_returned", from, ProgramStatus.SKELETON_PROPOSED,
                    body.comment(), null));
        });

        asyncJobs.runRegenerateSkeleton(id, body.comment());
        return store.require(id);
    }

    public ProgramRecord approveContent(UUID id, ContentApproveRequest body) {
        ProgramRecord record = store.require(id);
        assertEditable(record);
        if (record.getStatus() != ProgramStatus.VALIDATED
                && record.getStatus() != ProgramStatus.CONTENT_READY) {
            throw new IllegalStateException(
                    "Content approve requires validated/content_ready, was " + record.getStatus().jsonValue());
        }

        store.update(id, r -> {
            ProgramStatus from = r.getStatus();
            appendApproval(r.getProgram(), "content", true, body == null ? null : body.comment());
            if (from == ProgramStatus.CONTENT_READY) {
                // Allow approve before validate finishes in edge cases; prefer validated.
                ProgramStatusMachine.assertTransition(from, ProgramStatus.VALIDATED);
                r.setStatus(ProgramStatus.VALIDATED);
            }
            r.addHistory(new HistoryEntry(
                    Instant.now(), "content_approved", from, r.getStatus(),
                    body == null ? null : body.comment(), null));
        });
        return store.require(id);
    }

    public ProgramRecord returnContent(UUID id, ReturnRequest body) {
        ProgramRecord record = store.require(id);
        assertEditable(record);
        if (record.getStatus() != ProgramStatus.VALIDATED
                && record.getStatus() != ProgramStatus.CONTENT_READY
                && record.getStatus() != ProgramStatus.FAILED) {
            throw new IllegalStateException(
                    "Content return not allowed from " + record.getStatus().jsonValue());
        }

        store.update(id, r -> {
            ProgramStatus from = r.getStatus();
            ProgramStatusMachine.assertTransition(from, ProgramStatus.CONTENT_GENERATING);
            appendApproval(r.getProgram(), "content", false, body.comment());
            r.clearError();
            r.setStatus(ProgramStatus.CONTENT_GENERATING);
            r.setBusy(true);
            r.addHistory(new HistoryEntry(
                    Instant.now(), "content_returned", from, ProgramStatus.CONTENT_GENERATING,
                    body.comment(), null));
        });

        asyncJobs.runContentAndValidate(id);
        return store.require(id);
    }

    /**
     * Point regenerate: skeleton return or content section regenerate via Go.
     * Locked nodeIds with respectLocks yield 409 {@code node_locked}.
     */
    public ProgramRecord regenerate(UUID id, RegenerateRequest body) {
        ProgramRecord record = store.require(id);
        assertEditable(record);
        String scope = body.scope() == null ? "content" : body.scope();
        String comment = body.comment() == null ? "точечная перегенерация" : body.comment();

        if ("skeleton".equals(scope)) {
            ArrayNode nodeIds = toArrayNode(body.nodeIds());
            try {
                GoClient.SkeletonRegenerateResult result =
                        goClient.regenerateSkeleton(record.getProgram(), comment, nodeIds, true);
                store.update(id, r -> {
                    ProgramStatus from = r.getStatus();
                    if (result.variants() != null && !result.variants().isNull()) {
                        r.setSkeletonVariants(wrapVariants(result.variants()));
                    }
                    if (result.program() != null) {
                        ObjectNode prog = result.program();
                        prog.put("id", id.toString());
                        r.setProgram(prog);
                    }
                    ProgramStatusMachine.assertTransition(from, ProgramStatus.SKELETON_PROPOSED);
                    r.setStatus(ProgramStatus.SKELETON_PROPOSED);
                    r.setBusy(false);
                    r.clearError();
                    appendApproval(r.getProgram(), "skeleton", false, comment);
                    r.addHistory(new HistoryEntry(
                            Instant.now(), "skeleton_regenerated", from, ProgramStatus.SKELETON_PROPOSED,
                            comment, null));
                });
                return store.require(id);
            } catch (GoClientException ex) {
                if (ex.isNodeLocked()) {
                    throw new ConflictException(ex.getMessage());
                }
                throw ex;
            }
        }

        store.update(id, r -> {
            ProgramStatus from = r.getStatus();
            if (from != ProgramStatus.CONTENT_GENERATING) {
                ProgramStatusMachine.assertTransition(from, ProgramStatus.CONTENT_GENERATING);
                r.setStatus(ProgramStatus.CONTENT_GENERATING);
            }
            r.clearError();
            r.setBusy(true);
            r.addHistory(new HistoryEntry(
                    Instant.now(), "content_regenerate", from, ProgramStatus.CONTENT_GENERATING,
                    comment, null));
        });
        asyncJobs.runContentAndValidate(id);
        return store.require(id);
    }

    /** Rebalance hours via Go after manual edits (Java never computes hours). */
    public ProgramRecord allocate(UUID id) {
        ProgramRecord record = store.require(id);
        assertEditable(record);
        GoClient.AllocateResult result = goClient.allocate(record.getProgram());
        store.update(id, r -> {
            ObjectNode prog = result.program();
            prog.put("id", id.toString());
            r.setProgram(prog);
            r.addHistory(new HistoryEntry(
                    Instant.now(), "hours_reallocated", r.getStatus(), r.getStatus(),
                    "manual edit rebalance", null));
        });
        return store.require(id);
    }

    private ArrayNode toArrayNode(java.util.List<String> ids) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (ids != null) {
            ids.forEach(arr::add);
        }
        return arr;
    }

    public ProgramRecord lockNodes(UUID id, LockNodesRequest body) {
        ProgramRecord record = store.require(id);
        assertEditable(record);
        store.update(id, r -> {
            ObjectNode program = r.getProgram();
            for (String nodeId : body.nodeIds()) {
                applyLock(program, nodeId, body.locked());
            }
            r.setProgram(program);
            r.addHistory(new HistoryEntry(
                    Instant.now(),
                    body.locked() ? "nodes_locked" : "nodes_unlocked",
                    r.getStatus(),
                    r.getStatus(),
                    String.join(",", body.nodeIds()),
                    null));
        });
        return store.require(id);
    }

    private void applyLock(JsonNode node, String nodeId, boolean locked) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (nodeId.equals(obj.path("id").asText(null))) {
                ObjectNode meta = obj.withObject("/_meta");
                meta.put("locked", locked);
            }
            obj.fields().forEachRemaining(e -> applyLock(e.getValue(), nodeId, locked));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                applyLock(child, nodeId, locked);
            }
        }
    }

    /**
     * Synchronous end-to-end pipeline for automated tests: skips human approvals.
     */
    public GenerateResponse generateSync(GenerateRequest request) {
        UUID id = UUID.randomUUID();
        ObjectNode program = objectMapper.createObjectNode();
        program.put("schemaVersion", "1.1");
        program.put("id", id.toString());
        program.put("status", ProgramStatus.INTAKE.jsonValue());
        ObjectNode reqNode = program.putObject("request");
        reqNode.put("rawText", request.request());
        reqNode.putArray("attachments");
        ArrayNode clarifications = reqNode.putArray("clarifications");
        if (request.answers() != null) {
            for (Map.Entry<String, String> entry : request.answers().entrySet()) {
                ObjectNode c = clarifications.addObject();
                c.put("param", entry.getKey());
                c.put("question", "");
                c.put("answer", entry.getValue());
            }
        }
        reqNode.putArray("missing");
        reqNode.putArray("gapsReport");

        ProgramRecord record = new ProgramRecord(id, program);
        store.saveNew(record);

        try {
            GoClient.ParseResult parsed = goClient.parse(
                    request.request(),
                    record.getProgram().path("request").path("clarifications"));
            support.applyParseResult(record, parsed);

            if (parsed.missing() != null && parsed.missing().isArray() && !parsed.missing().isEmpty()) {
                // Auto-fill remaining gaps for test mode
                ArrayNode autoAnswers = objectMapper.createArrayNode();
                for (JsonNode missing : parsed.missing()) {
                    ObjectNode a = autoAnswers.addObject();
                    a.put("param", missing.asText());
                    a.put("question", "auto");
                    a.put("answer", "авто-значение для теста");
                }
                ObjectNode requestObj = record.getProgram().withObject("/request");
                ArrayNode existing = requestObj.withArray("clarifications");
                for (JsonNode a : autoAnswers) {
                    existing.add(a.deepCopy());
                }
                parsed = goClient.parse(request.request(), existing);
                support.applyParseResult(record, parsed);
            }

            GoClient.SkeletonGenerateResult skeleton = goClient.generateSkeleton(record.getProgram());
            record.setSkeletonVariants(wrapVariants(skeleton.variants()));
            JsonNode firstVariant = firstVariant(skeleton.variants());
            if (firstVariant == null) {
                throw new IllegalStateException("Go returned no skeleton variants");
            }
            String variantKey = firstVariant.path("key").asText("A");
            ObjectNode materialized = skeletonMaterializer.mergeVariant(record.getProgram(), firstVariant);
            materialized.put("status", ProgramStatus.SKELETON_APPROVED.jsonValue());
            record.setProgram(materialized);
            record.setSelectedVariantKey(variantKey);
            record.setStatus(ProgramStatus.SKELETON_APPROVED);
            appendApproval(record.getProgram(), "skeleton", true, "auto-approve (test mode)");
            record.addHistory(new HistoryEntry(
                    Instant.now(), "skeleton_auto_approved", ProgramStatus.SKELETON_PROPOSED,
                    ProgramStatus.SKELETON_APPROVED, "test mode", variantKey));

            record.setStatus(ProgramStatus.CONTENT_GENERATING);
            GoClient.ContentGenerateResult content = goClient.generateContent(record.getProgram(), null);
            ObjectNode contentProgram = content.program();
            contentProgram.put("id", id.toString());
            contentProgram.put("status", ProgramStatus.CONTENT_READY.jsonValue());
            record.setProgram(contentProgram);
            record.setStatus(ProgramStatus.CONTENT_READY);
            if (content.issues() != null) {
                record.setIssues(content.issues());
            }
            appendApproval(record.getProgram(), "content", true, "auto-approve (test mode)");

            GoClient.ValidateResult validation = goClient.validate(record.getProgram());
            record.setValidationReport(validation.validationReport());
            record.setIssues(validation.issues());
            ObjectNode prog = record.getProgram();
            prog.set("validationReport", validation.validationReport());
            prog.put("status", ProgramStatus.VALIDATED.jsonValue());
            record.setProgram(prog);
            record.setStatus(ProgramStatus.VALIDATED);
            record.setBusy(false);
            record.clearError();

            return new GenerateResponse(
                    id,
                    record.getStatus().jsonValue(),
                    record.getProgram(),
                    record.getIssues(),
                    record.getValidationReport()
            );
        } catch (Exception ex) {
            record.markFailed(ex.getMessage());
            throw ex;
        }
    }

    private JsonNode wrapVariants(JsonNode variants) {
        if (variants != null && variants.isObject() && variants.has("variants")) {
            return variants;
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("variants", variants == null ? objectMapper.createArrayNode() : variants);
        return wrapper;
    }

    private static JsonNode firstVariant(JsonNode variants) {
        JsonNode list = variants;
        if (variants != null && variants.isObject() && variants.has("variants")) {
            list = variants.get("variants");
        }
        if (list != null && list.isArray() && !list.isEmpty()) {
            return list.get(0);
        }
        return null;
    }

    private static JsonNode findVariant(JsonNode skeletonVariants, String key) {
        JsonNode list = skeletonVariants;
        if (skeletonVariants != null && skeletonVariants.isObject() && skeletonVariants.has("variants")) {
            list = skeletonVariants.get("variants");
        }
        if (list == null || !list.isArray()) {
            return null;
        }
        for (JsonNode v : list) {
            if (key.equals(v.path("key").asText())) {
                return v;
            }
        }
        return null;
    }

    private void appendApproval(ObjectNode program, String stage, boolean approved, String comment) {
        ArrayNode approvals = program.withArray("approvals");
        ObjectNode entry = approvals.addObject();
        entry.put("stage", stage);
        entry.put("approved", approved);
        entry.put("at", Instant.now().toString());
        if (comment != null) {
            entry.put("comment", comment);
        }
    }

    private static void assertEditable(ProgramRecord record) {
        if (ProgramStatusMachine.isMutationBlocked(record.getStatus(), record.isBusy())) {
            throw new ConflictException(
                    "Program is busy generating/validating (status="
                            + record.getStatus().jsonValue() + ", busy=" + record.isBusy() + ")");
        }
    }
}
