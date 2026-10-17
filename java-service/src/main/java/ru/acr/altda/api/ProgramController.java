package ru.acr.altda.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.acr.altda.api.dto.ClarificationAnswerRequest;
import ru.acr.altda.api.dto.ContentApproveRequest;
import ru.acr.altda.api.dto.CreateProgramRequest;
import ru.acr.altda.api.dto.ErrorResponse;
import ru.acr.altda.api.dto.LockNodesRequest;
import ru.acr.altda.api.dto.ProgramResponse;
import ru.acr.altda.api.dto.ProgramSummaryResponse;
import ru.acr.altda.api.dto.RegenerateRequest;
import ru.acr.altda.api.dto.ReturnRequest;
import ru.acr.altda.api.dto.SkeletonApproveRequest;
import ru.acr.altda.domain.ProgramRecord;
import ru.acr.altda.service.ProgramService;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/programs")
public class ProgramController {

    private final ProgramService programService;
    private final ProgramMapper mapper;

    public ProgramController(ProgramService programService, ProgramMapper mapper) {
        this.programService = programService;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<ProgramResponse> create(@Valid @RequestBody CreateProgramRequest request) {
        ProgramRecord record = programService.create(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toResponse(record));
    }

    @GetMapping
    public List<ProgramSummaryResponse> list() {
        return programService.list().stream()
                .sorted(Comparator.comparing(ProgramRecord::getUpdatedAt).reversed())
                .map(mapper::toSummary)
                .toList();
    }

    @GetMapping("/{id}")
    public ProgramResponse get(@PathVariable UUID id) {
        return mapper.toResponse(programService.get(id));
    }

    @PostMapping("/{id}/clarifications")
    public ProgramResponse clarifications(
            @PathVariable UUID id,
            @Valid @RequestBody ClarificationAnswerRequest body
    ) {
        return mapper.toResponse(programService.answerClarifications(id, body));
    }

    @PostMapping("/{id}/skeleton/approve")
    public ProgramResponse approveSkeleton(
            @PathVariable UUID id,
            @Valid @RequestBody SkeletonApproveRequest body
    ) {
        return mapper.toResponse(programService.approveSkeleton(id, body));
    }

    @PostMapping("/{id}/skeleton/return")
    public ProgramResponse returnSkeleton(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnRequest body
    ) {
        return mapper.toResponse(programService.returnSkeleton(id, body));
    }

    @PostMapping("/{id}/content/approve")
    public ProgramResponse approveContent(
            @PathVariable UUID id,
            @RequestBody(required = false) ContentApproveRequest body
    ) {
        return mapper.toResponse(programService.approveContent(id, body == null
                ? new ContentApproveRequest(null)
                : body));
    }

    @PostMapping("/{id}/content/return")
    public ProgramResponse returnContent(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnRequest body
    ) {
        return mapper.toResponse(programService.returnContent(id, body));
    }

    @PostMapping("/{id}/regenerate")
    public ProgramResponse regenerate(
            @PathVariable UUID id,
            @RequestBody RegenerateRequest body
    ) {
        return mapper.toResponse(programService.regenerate(id, body == null
                ? new RegenerateRequest(null, null, "content")
                : body));
    }

    @PostMapping("/{id}/lock")
    public ProgramResponse lock(
            @PathVariable UUID id,
            @Valid @RequestBody LockNodesRequest body
    ) {
        return mapper.toResponse(programService.lockNodes(id, body));
    }

    /** Phase 3 placeholder: .docx export not implemented yet. */
    @GetMapping("/{id}/export/docx")
    public ResponseEntity<ErrorResponse> exportDocx(@PathVariable UUID id) {
        programService.get(id);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(new ErrorResponse(
                        "not_implemented",
                        "Выгрузка .docx будет в фазе 3 (poi-tl). Скачайте JSON на вкладке «Выгрузка»."));
    }
}
