package ru.acr.altda.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.acr.altda.config.AsyncConfig;
import ru.acr.altda.domain.HistoryEntry;
import ru.acr.altda.domain.ProgramRecord;
import ru.acr.altda.domain.ProgramStatus;
import ru.acr.altda.domain.ProgramStatusMachine;
import ru.acr.altda.go.GoClient;
import ru.acr.altda.store.ProgramStore;

import java.time.Instant;
import java.util.UUID;

@Service
public class ProgramAsyncJobs {

    private static final Logger log = LoggerFactory.getLogger(ProgramAsyncJobs.class);

    private final ProgramStore store;
    private final GoClient goClient;
    private final ObjectMapper objectMapper;
    private final ProgramServiceSupport support;

    public ProgramAsyncJobs(
            ProgramStore store,
            GoClient goClient,
            ObjectMapper objectMapper,
            ProgramServiceSupport support
    ) {
        this.store = store;
        this.goClient = goClient;
        this.objectMapper = objectMapper;
        this.support = support;
    }

    @Async(AsyncConfig.PROGRAM_EXECUTOR)
    public void runParseAndMaybeSkeleton(UUID id) {
        try {
            store.update(id, r -> r.setBusy(true));
            ProgramRecord record = store.require(id);
            String rawText = record.getProgram().path("request").path("rawText").asText();
            JsonNode clarifications = record.getProgram().path("request").path("clarifications");

            GoClient.ParseResult parsed = goClient.parse(rawText, clarifications);
            store.update(id, r -> {
                support.applyParseResult(r, parsed);
                boolean missing = parsed.missing() != null
                        && parsed.missing().isArray()
                        && !parsed.missing().isEmpty();
                ProgramStatus from = r.getStatus();
                if (missing) {
                    ProgramStatusMachine.assertTransition(from, ProgramStatus.CLARIFYING);
                    r.setStatus(ProgramStatus.CLARIFYING);
                    r.setBusy(false);
                    r.clearError();
                    r.addHistory(new HistoryEntry(
                            Instant.now(), "clarifying", from, ProgramStatus.CLARIFYING, null, null));
                } else {
                    r.setBusy(true);
                }
            });

            record = store.require(id);
            if (record.getStatus() == ProgramStatus.CLARIFYING) {
                return;
            }

            generateSkeletonInternal(id);
        } catch (Exception ex) {
            log.error("Parse/skeleton failed for {}", id, ex);
            store.update(id, r -> r.markFailed(ex.getMessage()));
        }
    }

    @Async(AsyncConfig.PROGRAM_EXECUTOR)
    public void runRegenerateSkeleton(UUID id, String comment) {
        try {
            ProgramRecord record = store.require(id);
            GoClient.SkeletonRegenerateResult result = goClient.regenerateSkeleton(
                    record.getProgram(), comment, null, true);
            store.update(id, r -> {
                if (result.variants() != null && !result.variants().isNull()) {
                    r.setSkeletonVariants(wrapVariants(result.variants()));
                }
                if (result.program() != null) {
                    ObjectNode prog = result.program();
                    prog.put("id", id.toString());
                    r.setProgram(prog);
                }
                ProgramStatus from = r.getStatus();
                ProgramStatusMachine.assertTransition(from, ProgramStatus.SKELETON_PROPOSED);
                r.setStatus(ProgramStatus.SKELETON_PROPOSED);
                r.setBusy(false);
                r.clearError();
                r.addHistory(new HistoryEntry(
                        Instant.now(), "skeleton_regenerated", from, ProgramStatus.SKELETON_PROPOSED,
                        comment, null));
            });
        } catch (Exception ex) {
            log.error("Regenerate skeleton failed for {}", id, ex);
            store.update(id, r -> r.markFailed(ex.getMessage()));
        }
    }

    @Async(AsyncConfig.PROGRAM_EXECUTOR)
    public void runContentAndValidate(UUID id) {
        try {
            store.update(id, r -> {
                ProgramStatus from = r.getStatus();
                if (from == ProgramStatus.SKELETON_APPROVED || from == ProgramStatus.CONTENT_GENERATING) {
                    if (from == ProgramStatus.SKELETON_APPROVED) {
                        ProgramStatusMachine.assertTransition(from, ProgramStatus.CONTENT_GENERATING);
                    }
                    r.setStatus(ProgramStatus.CONTENT_GENERATING);
                }
                r.setBusy(true);
            });

            ProgramRecord record = store.require(id);
            GoClient.ContentGenerateResult content = goClient.generateContent(record.getProgram(), null);
            store.update(id, r -> {
                ObjectNode prog = content.program();
                prog.put("id", id.toString());
                prog.put("status", ProgramStatus.CONTENT_READY.jsonValue());
                r.setProgram(prog);
                if (content.issues() != null) {
                    r.setIssues(content.issues());
                }
                ProgramStatusMachine.assertTransition(r.getStatus(), ProgramStatus.CONTENT_READY);
                r.setStatus(ProgramStatus.CONTENT_READY);
            });

            record = store.require(id);
            GoClient.ValidateResult validation = goClient.validate(record.getProgram());
            store.update(id, r -> {
                r.setValidationReport(validation.validationReport());
                r.setIssues(validation.issues());
                ObjectNode prog = r.getProgram();
                prog.set("validationReport", validation.validationReport());
                prog.put("status", ProgramStatus.VALIDATED.jsonValue());
                r.setProgram(prog);
                ProgramStatusMachine.assertTransition(r.getStatus(), ProgramStatus.VALIDATED);
                r.setStatus(ProgramStatus.VALIDATED);
                r.setBusy(false);
                r.clearError();
                r.addHistory(new HistoryEntry(
                        Instant.now(), "validated", ProgramStatus.CONTENT_READY, ProgramStatus.VALIDATED,
                        null, null));
            });
        } catch (Exception ex) {
            log.error("Content/validate failed for {}", id, ex);
            store.update(id, r -> r.markFailed(ex.getMessage()));
        }
    }

    private void generateSkeletonInternal(UUID id) {
        ProgramRecord record = store.require(id);
        GoClient.SkeletonGenerateResult skeleton = goClient.generateSkeleton(record.getProgram());
        store.update(id, r -> {
            r.setSkeletonVariants(wrapVariants(skeleton.variants()));
            ProgramStatus from = r.getStatus();
            ProgramStatusMachine.assertTransition(from, ProgramStatus.SKELETON_PROPOSED);
            r.setStatus(ProgramStatus.SKELETON_PROPOSED);
            r.setBusy(false);
            r.clearError();
            r.addHistory(new HistoryEntry(
                    Instant.now(), "skeleton_proposed", from, ProgramStatus.SKELETON_PROPOSED, null, null));
        });
    }

    private JsonNode wrapVariants(JsonNode variants) {
        if (variants != null && variants.isObject() && variants.has("variants")) {
            return variants;
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("variants", variants == null ? objectMapper.createArrayNode() : variants);
        return wrapper;
    }
}
