package ru.acr.altda.store;

import org.springframework.stereotype.Component;
import ru.acr.altda.domain.ProgramRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-memory program store backed by {@link ConcurrentHashMap}.
 * Updates go through {@link #update(UUID, long, Consumer)} with optimistic locking on {@code version}.
 */
@Component
public class ProgramStore {

    private final ConcurrentHashMap<UUID, ProgramRecord> programs = new ConcurrentHashMap<>();

    public ProgramRecord saveNew(ProgramRecord record) {
        ProgramRecord previous = programs.putIfAbsent(record.getId(), record);
        if (previous != null) {
            throw new IllegalStateException("Program already exists: " + record.getId());
        }
        return record;
    }

    public Optional<ProgramRecord> find(UUID id) {
        return Optional.ofNullable(programs.get(id));
    }

    public ProgramRecord require(UUID id) {
        ProgramRecord record = programs.get(id);
        if (record == null) {
            throw new ProgramNotFoundException(id);
        }
        return record;
    }

    public List<ProgramRecord> findAll() {
        return new ArrayList<>(programs.values());
    }

    /**
     * Apply a mutation if {@code expectedVersion} matches the current version.
     *
     * @throws OptimisticLockException on version mismatch
     */
    public ProgramRecord update(UUID id, long expectedVersion, Consumer<ProgramRecord> mutator) {
        ProgramRecord record = require(id);
        synchronized (record) {
            if (record.getVersion() != expectedVersion) {
                throw new OptimisticLockException(id, expectedVersion, record.getVersion());
            }
            mutator.accept(record);
            return record;
        }
    }

    /** Mutate under the record lock without a client-supplied version (internal async jobs). */
    public ProgramRecord update(UUID id, Consumer<ProgramRecord> mutator) {
        ProgramRecord record = require(id);
        synchronized (record) {
            mutator.accept(record);
            return record;
        }
    }
}
