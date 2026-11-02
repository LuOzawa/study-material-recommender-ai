package com.recommend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Payloads exchanged with the FastAPI AI service.  The wire format is
 * snake_case, which is why every record carries {@link JsonNaming}.
 */
public final class AiDtos {

    private AiDtos() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Citation(int index, Long materialId, String title, Integer chunkIndex,
                           Double score, String snippet) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reference(Long materialId, String title, Integer chunkIndex,
                            Double score, String snippet) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResult(String answer, List<Citation> citations, List<Reference> references,
                             String route, String query, Integer rewrites, String model,
                             Long latencyMs) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Match(Long materialId, String title, Integer chunkIndex, Double score,
                        String snippet, String tags) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResult(String query, List<Match> matches) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SummaryResult(String summary, List<Citation> citations, Boolean grounded,
                                Integer chunks, String model) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SuggestionResult(String suggestions, List<Reference> references, String model) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexResult(Long materialId, String status, Integer chunks) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RebuildResult(int indexed, int skipped, int removed, List<Long> failed,
                                int chunks, int durationMs) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexStats(int materials, int chunks, int embeddingDim, String lastIndexedAt,
                             String llmModel, String embeddingModel) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MaterialPayload(Long id, String title, String description, String tags,
                                  Long categoryId, String categoryName, String status,
                                  String author, Double ratingAvg, String updatedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MaterialPage(List<MaterialPayload> items, int page, int size,
                               boolean hasMore, long total) {
    }
}
