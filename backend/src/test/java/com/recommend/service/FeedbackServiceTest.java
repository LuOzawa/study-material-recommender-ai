package com.recommend.service;

import com.recommend.entity.Material;
import com.recommend.entity.RecommendationFeedback;
import com.recommend.repository.MaterialRepository;
import com.recommend.repository.RecommendationFeedbackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock private RecommendationFeedbackRepository feedbackRepository;
    @Mock private MaterialRepository materialRepository;

    @InjectMocks
    private FeedbackService feedbackService;

    @Test
    void submitCreatesSingleFeedback() {
        when(materialRepository.findById(1L)).thenReturn(Optional.of(material(1L, Material.Status.ACTIVE)));
        when(feedbackRepository.findByUserIdAndMaterialId(2L, 1L)).thenReturn(Optional.empty());
        when(feedbackRepository.save(any(RecommendationFeedback.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RecommendationFeedback.FeedbackType result = feedbackService.submit(
                2L, 1L, RecommendationFeedback.FeedbackType.LIKE);

        assertThat(result).isEqualTo(RecommendationFeedback.FeedbackType.LIKE);
        verify(feedbackRepository).save(any(RecommendationFeedback.class));
    }

    @Test
    void submitSwitchesExistingFeedbackWithoutCreatingRow() {
        RecommendationFeedback existing = new RecommendationFeedback();
        existing.setUserId(2L);
        existing.setMaterialId(1L);
        existing.setFeedback(RecommendationFeedback.FeedbackType.LIKE);
        when(materialRepository.findById(1L)).thenReturn(Optional.of(material(1L, Material.Status.ACTIVE)));
        when(feedbackRepository.findByUserIdAndMaterialId(2L, 1L)).thenReturn(Optional.of(existing));
        when(feedbackRepository.save(existing)).thenReturn(existing);

        feedbackService.submit(2L, 1L, RecommendationFeedback.FeedbackType.DISLIKE);

        assertThat(existing.getFeedback()).isEqualTo(RecommendationFeedback.FeedbackType.DISLIKE);
        verify(feedbackRepository).save(existing);
    }

    @Test
    void submitRejectsInactiveMaterial() {
        when(materialRepository.findById(9L)).thenReturn(Optional.of(material(9L, Material.Status.PENDING)));

        assertThatThrownBy(() -> feedbackService.submit(2L, 9L, RecommendationFeedback.FeedbackType.LIKE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("资料不存在或已下架");
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void cancelDeletesByUserAndMaterial() {
        feedbackService.cancel(2L, 1L);

        verify(feedbackRepository).deleteByUserIdAndMaterialId(2L, 1L);
    }

    @Test
    void mapByUserUsesMaterialIdAsKey() {
        RecommendationFeedback first = new RecommendationFeedback();
        first.setUserId(2L);
        first.setMaterialId(1L);
        first.setFeedback(RecommendationFeedback.FeedbackType.LIKE);
        RecommendationFeedback second = new RecommendationFeedback();
        second.setUserId(2L);
        second.setMaterialId(2L);
        second.setFeedback(RecommendationFeedback.FeedbackType.DISLIKE);
        when(feedbackRepository.findByUserId(2L)).thenReturn(List.of(first, second));

        Map<Long, RecommendationFeedback.FeedbackType> result = feedbackService.mapByUser(2L);

        assertThat(result).containsEntry(1L, RecommendationFeedback.FeedbackType.LIKE)
                .containsEntry(2L, RecommendationFeedback.FeedbackType.DISLIKE);
    }

    private Material material(Long id, Material.Status status) {
        Material material = new Material();
        material.setId(id);
        material.setStatus(status);
        return material;
    }
}
