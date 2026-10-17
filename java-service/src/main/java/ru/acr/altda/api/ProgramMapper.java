package ru.acr.altda.api;

import org.springframework.stereotype.Component;
import ru.acr.altda.api.dto.ProgramResponse;
import ru.acr.altda.api.dto.ProgramSummaryResponse;
import ru.acr.altda.domain.ProgramRecord;

@Component
public class ProgramMapper {

    public ProgramResponse toResponse(ProgramRecord record) {
        String title = record.getProgram().path("header").path("title").asText(null);
        if (title == null) {
            title = record.getProgram().path("request").path("params").path("title").asText(null);
        }
        return new ProgramResponse(
                record.getId(),
                record.getStatus().jsonValue(),
                record.isBusy(),
                record.getError(),
                record.getVersion(),
                record.getSelectedVariantKey(),
                record.getSkeletonVariants(),
                record.getValidationReport(),
                record.getIssues(),
                record.getProgram(),
                record.getHistory(),
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }

    public ProgramSummaryResponse toSummary(ProgramRecord record) {
        String title = record.getProgram().path("header").path("title").asText(null);
        if (title == null) {
            title = record.getProgram().path("request").path("params").path("title").asText("(без названия)");
        }
        return new ProgramSummaryResponse(
                record.getId(),
                record.getStatus().jsonValue(),
                title,
                record.isBusy(),
                record.getError(),
                record.getVersion(),
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }
}
