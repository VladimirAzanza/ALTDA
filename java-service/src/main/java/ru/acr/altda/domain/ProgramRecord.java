package ru.acr.altda.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory заявка aggregate: schema program JSON plus orchestration metadata.
 * {@link #version} supports optimistic locking on concurrent updates.
 */
public class ProgramRecord {

    private final UUID id;
    private final AtomicLong version = new AtomicLong(0);
    private volatile ProgramStatus status;
    private volatile ObjectNode program;
    private volatile JsonNode skeletonVariants;
    private volatile String selectedVariantKey;
    private volatile JsonNode validationReport;
    private volatile JsonNode issues;
    private final List<HistoryEntry> history = new ArrayList<>();
    private volatile boolean busy;
    private volatile String error;
    private final Instant createdAt;
    private volatile Instant updatedAt;

    public ProgramRecord(UUID id, ObjectNode program) {
        this.id = id;
        this.program = program;
        this.status = ProgramStatus.INTAKE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.busy = false;
    }

    public UUID getId() {
        return id;
    }

    /** Monotonic version bumped on every successful mutation of this record. */
    public long getVersion() {
        return version.get();
    }

    public long bumpVersion() {
        long next = version.incrementAndGet();
        touch();
        return next;
    }

    public ProgramStatus getStatus() {
        return status;
    }

    public void setStatus(ProgramStatus status) {
        this.status = status;
        if (status.isSchemaStatus()) {
            program.put("status", status.jsonValue());
        }
        bumpVersion();
    }

    public ObjectNode getProgram() {
        return program;
    }

    public void setProgram(ObjectNode program) {
        this.program = program;
        bumpVersion();
    }

    public JsonNode getSkeletonVariants() {
        return skeletonVariants;
    }

    public void setSkeletonVariants(JsonNode skeletonVariants) {
        this.skeletonVariants = skeletonVariants;
        bumpVersion();
    }

    public String getSelectedVariantKey() {
        return selectedVariantKey;
    }

    public void setSelectedVariantKey(String selectedVariantKey) {
        this.selectedVariantKey = selectedVariantKey;
        bumpVersion();
    }

    public JsonNode getValidationReport() {
        return validationReport;
    }

    public void setValidationReport(JsonNode validationReport) {
        this.validationReport = validationReport;
        bumpVersion();
    }

    public JsonNode getIssues() {
        return issues;
    }

    public void setIssues(JsonNode issues) {
        this.issues = issues;
        bumpVersion();
    }

    public List<HistoryEntry> getHistory() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    public void addHistory(HistoryEntry entry) {
        synchronized (history) {
            history.add(entry);
        }
        bumpVersion();
    }

    public boolean isBusy() {
        return busy;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
        bumpVersion();
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
        bumpVersion();
    }

    public void markFailed(String message) {
        this.error = message;
        this.busy = false;
        this.status = ProgramStatus.FAILED;
        bumpVersion();
    }

    public void clearError() {
        this.error = null;
        bumpVersion();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
