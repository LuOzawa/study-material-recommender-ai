package com.recommend.service;

import com.recommend.entity.Material;
import com.recommend.entity.Recommendation;
import com.recommend.entity.RecommendationFeedback;
import com.recommend.repository.MaterialRepository;
import com.recommend.repository.RecommendationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock private RecommendationRepository recommendationRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private UserPreferenceService preferenceService;
    @Mock private FeedbackService feedbackService;

    @InjectMocks
    private RecommendationService recommendationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(recommendationService, "topN", 10);
        ReflectionTestUtils.setField(recommendationService, "pythonScript", "script.py");
        ReflectionTestUtils.setField(recommendationService, "pythonCommand", "python3");
    }

    @Test
    void feedbackReranksLikeUpAndDropsDislike() {
        when(preferenceService.getPreferenceData(1L)).thenReturn(new UserPreferenceService.PreferenceData(
                Set.of(), Set.of(), Set.of(), 10, false));
        when(recommendationRepository.findByUserIdOrderByScoreDesc(eq(1L), any())).thenReturn(List.of(
                recommendation(1L, 3.0), recommendation(2L, 2.0), recommendation(3L, 1.0)));
        when(materialRepository.findActiveByIds(anyList())).thenReturn(List.of(
                material(1L), material(2L), material(3L)));
        when(feedbackService.mapByUser(1L)).thenReturn(Map.of(
                1L, RecommendationFeedback.FeedbackType.DISLIKE,
                2L, RecommendationFeedback.FeedbackType.LIKE));

        List<Material> result = recommendationService.getRecommendations(1L);

        assertThat(result).extracting(Material::getId).containsExactly(2L, 3L);
    }

    @Test
    void allDislikedFallsBackToUnfilteredList() {
        when(preferenceService.getPreferenceData(1L)).thenReturn(new UserPreferenceService.PreferenceData(
                Set.of(), Set.of(), Set.of(), 10, false));
        when(recommendationRepository.findByUserIdOrderByScoreDesc(eq(1L), any())).thenReturn(List.of(
                recommendation(1L, 3.0)));
        when(materialRepository.findActiveByIds(anyList())).thenReturn(List.of(material(1L)));
        when(feedbackService.mapByUser(1L)).thenReturn(Map.of(
                1L, RecommendationFeedback.FeedbackType.DISLIKE));

        List<Material> result = recommendationService.getRecommendations(1L);

        assertThat(result).extracting(Material::getId).containsExactly(1L);
    }

    @Test
    void emptyRecommendationsFallBackToHotMaterials() {
        when(preferenceService.getPreferenceData(1L)).thenReturn(new UserPreferenceService.PreferenceData(
                Set.of(), Set.of(), Set.of(), 2, false));
        when(recommendationRepository.findByUserIdOrderByScoreDesc(eq(1L), any())).thenReturn(List.of());
        when(feedbackService.mapByUser(1L)).thenReturn(Map.of());
        when(materialRepository.findTopByViewCount(any())).thenReturn(List.of(
                material(1L), material(2L), material(3L)));

        List<Material> result = recommendationService.getRecommendations(1L);

        assertThat(result).extracting(Material::getId).containsExactly(1L, 2L);
        assertThat(result).allSatisfy(material -> {
            assertThat(material.getRecommendationAlgorithm()).isEqualTo("HOT");
            assertThat(material.getRecommendationScore()).isNull();
        });
    }

    @Test
    void pythonCommandIsConfigurable() {
        String[] command = recommendationService.buildPythonCommand("--user-id", "2");

        assertThat(command).hasSize(4);
        assertThat(command[0]).isEqualTo("python3");
        assertThat(command[2]).isEqualTo("--user-id");
        assertThat(command[3]).isEqualTo("2");
    }

    private Recommendation recommendation(Long materialId, double score) {
        Recommendation recommendation = new Recommendation();
        recommendation.setMaterialId(materialId);
        recommendation.setScore(BigDecimal.valueOf(score));
        return recommendation;
    }

    private Material material(Long id) {
        Material material = new Material();
        material.setId(id);
        material.setStatus(Material.Status.ACTIVE);
        return material;
    }
}
