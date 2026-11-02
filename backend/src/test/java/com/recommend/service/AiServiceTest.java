package com.recommend.service;

import com.recommend.client.AiServiceClient;
import com.recommend.dto.AiDtos;
import com.recommend.entity.AiConversation;
import com.recommend.entity.Material;
import com.recommend.entity.User;
import com.recommend.repository.AiConversationRepository;
import com.recommend.repository.MaterialRepository;
import com.recommend.repository.UserFavoriteRepository;
import com.recommend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceTest {

    private AiConversationRepository conversationRepository;
    private MaterialRepository materialRepository;
    private UserRepository userRepository;
    private UserFavoriteRepository favoriteRepository;
    private AiServiceClient client;
    private AiMaterialSnapshotService snapshotService;
    private AiService aiService;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(AiConversationRepository.class);
        materialRepository = mock(MaterialRepository.class);
        userRepository = mock(UserRepository.class);
        favoriteRepository = mock(UserFavoriteRepository.class);
        client = mock(AiServiceClient.class);
        snapshotService = mock(AiMaterialSnapshotService.class);
        aiService = new AiService(conversationRepository, materialRepository, userRepository,
                favoriteRepository, client, snapshotService);
    }

    @Test
    void chatSendsChronologicalHistoryAndPersistsAnswer() {
        AiConversation older = conversation("Q1", "A1");
        AiConversation newer = conversation("Q2", "A2");
        when(conversationRepository.findByUserIdOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(List.of(newer, older));
        when(client.chat(eq(1L), eq("Q3"), any())).thenReturn(new AiDtos.ChatResult(
                "A3", List.of(), List.of(), "materials", "Q3", 0, "m", 10L));

        AiDtos.ChatResult result = aiService.chat(1L, "Q3");

        assertThat(result.answer()).isEqualTo("A3");
        ArgumentCaptor<List<Map<String, String>>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(client).chat(eq(1L), eq("Q3"), historyCaptor.capture());
        assertThat(historyCaptor.getValue()).extracting(item -> item.get("question"))
                .containsExactly("Q1", "Q2");

        ArgumentCaptor<AiConversation> saved = ArgumentCaptor.forClass(AiConversation.class);
        verify(conversationRepository).save(saved.capture());
        assertThat(saved.getValue().getAnswer()).isEqualTo("A3");
        assertThat(saved.getValue().getType()).isEqualTo(AiConversation.ConvType.CHAT);
    }

    @Test
    void summarizeRejectsNonActiveMaterial() {
        Material material = new Material();
        material.setId(5L);
        material.setStatus(Material.Status.PENDING);
        when(materialRepository.findById(5L)).thenReturn(Optional.of(material));

        assertThatThrownBy(() -> aiService.summarize(1L, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未上架");
    }

    @Test
    void summarizePassesSnapshotAndStoresConversation() {
        Material material = new Material();
        material.setId(5L);
        material.setTitle("数学一真题");
        material.setStatus(Material.Status.ACTIVE);
        AiDtos.MaterialPayload payload = new AiDtos.MaterialPayload(
                5L, "数学一真题", "d", "数学", 1L, "数学", "ACTIVE", "a", 4.5, "2026-10-01T08:00:00");
        when(materialRepository.findById(5L)).thenReturn(Optional.of(material));
        when(snapshotService.snapshot(material)).thenReturn(payload);
        when(client.summarize(1L, 5L, payload)).thenReturn(new AiDtos.SummaryResult(
                "摘要", List.of(), true, 2, "m"));

        AiDtos.SummaryResult result = aiService.summarize(1L, 5L);

        assertThat(result.summary()).isEqualTo("摘要");
        verify(conversationRepository).save(any());
        verify(client).summarize(1L, 5L, payload);
    }

    @Test
    void suggestionsIncludeProfileAndFavorites() {
        User user = new User();
        user.setId(1L);
        user.setTargetSchool("中山大学");
        user.setMajor("计算机");
        user.setStudyPhase(User.StudyPhase.INTENSIVE);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(favoriteRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(client.suggest(eq(1L), eq("中山大学"), eq("计算机"), eq("强化期"), any()))
                .thenReturn(new AiDtos.SuggestionResult("计划", List.of(), "m"));

        AiDtos.SuggestionResult result = aiService.getSuggestions(1L);

        assertThat(result.suggestions()).isEqualTo("计划");
        verify(client).suggest(1L, "中山大学", "计算机", "强化期", List.of());
    }

    private AiConversation conversation(String question, String answer) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(1L);
        conversation.setQuestion(question);
        conversation.setAnswer(answer);
        conversation.setType(AiConversation.ConvType.CHAT);
        return conversation;
    }
}
