package com.recommend.service;

import com.recommend.client.AiServiceClient;
import com.recommend.dto.AiDtos;
import com.recommend.entity.AiConversation;
import com.recommend.entity.Material;
import com.recommend.entity.User;
import com.recommend.entity.UserFavorite;
import com.recommend.repository.AiConversationRepository;
import com.recommend.repository.MaterialRepository;
import com.recommend.repository.UserFavoriteRepository;
import com.recommend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Application-facing AI facade.  All model work happens in the FastAPI service
 * (LangGraph + vLLM + pgvector); this class only loads context from PostgreSQL and
 * persists conversations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final AiConversationRepository conversationRepository;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final UserFavoriteRepository favoriteRepository;
    private final AiServiceClient aiServiceClient;
    private final AiMaterialSnapshotService snapshotService;

    public AiDtos.ChatResult chat(Long userId, String question) {
        List<Map<String, String>> history = recentChatHistory(userId);
        AiDtos.ChatResult result = aiServiceClient.chat(userId, question, history);
        saveConversation(userId, question, result.answer(), AiConversation.ConvType.CHAT, null);
        return result;
    }

    public AiDtos.SummaryResult summarize(Long userId, Long materialId) {
        Material material = materialRepository.findById(materialId)
                .filter(item -> item.getStatus() == Material.Status.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("资料不存在或未上架"));

        AiDtos.SummaryResult result = aiServiceClient.summarize(
                userId, materialId, snapshotService.snapshot(material));
        saveConversation(userId, "总结资料：" + material.getTitle(), result.summary(),
                AiConversation.ConvType.SUMMARY, materialId);
        return result;
    }

    public AiDtos.SuggestionResult getSuggestions(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        AiDtos.SuggestionResult result = aiServiceClient.suggest(
                userId,
                user.getTargetSchool(),
                user.getMajor(),
                phaseLabel(user.getStudyPhase()),
                favoriteTitles(userId));
        saveConversation(userId, "生成个性化备考建议", result.suggestions(),
                AiConversation.ConvType.SUGGESTION, null);
        return result;
    }

    public Map<String, Object> semanticSearch(Long userId, String query) {
        AiDtos.SearchResult result = aiServiceClient.search(query, 8);

        Set<Long> orderedIds = new LinkedHashSet<>();
        for (AiDtos.Match match : Optional.ofNullable(result.matches()).orElse(List.of())) {
            orderedIds.add(match.materialId());
        }
        Map<Long, Material> materialsById = new LinkedHashMap<>();
        if (!orderedIds.isEmpty()) {
            for (Material material : materialRepository.findActiveByIds(new ArrayList<>(orderedIds))) {
                materialsById.put(material.getId(), material);
            }
        }

        List<Map<String, Object>> materials = new ArrayList<>();
        Set<String> keywords = new LinkedHashSet<>();
        for (Long id : orderedIds) {
            Material material = materialsById.get(id);
            if (material == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", material.getId());
            item.put("title", material.getTitle());
            item.put("tags", material.getTags());
            item.put("description", material.getDescription());
            item.put("categoryId", material.getCategoryId());
            item.put("ratingAvg", material.getRatingAvg());
            item.put("viewCount", material.getViewCount());
            materials.add(item);
            if (material.getTags() != null) {
                for (String tag : material.getTags().split("[,，]")) {
                    String cleaned = tag.trim();
                    if (!cleaned.isEmpty() && keywords.size() < 6) {
                        keywords.add(cleaned);
                    }
                }
            }
        }

        saveConversation(userId, query, "pgvector 语义检索命中 " + materials.size() + " 条资料",
                AiConversation.ConvType.SEARCH, null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("keywords", new ArrayList<>(keywords));
        response.put("mode", "pgvector");
        response.put("materials", materials);
        response.put("matches", Optional.ofNullable(result.matches()).orElse(List.of()));
        return response;
    }

    public List<AiConversation> getHistory(Long userId) {
        return conversationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20));
    }

    // ------------------------------------------------------------------
    private List<Map<String, String>> recentChatHistory(Long userId) {
        List<AiConversation> recent = conversationRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(0, 3));
        List<Map<String, String>> history = new ArrayList<>();
        for (int index = recent.size() - 1; index >= 0; index--) {
            AiConversation conversation = recent.get(index);
            if (conversation.getType() == AiConversation.ConvType.CHAT
                    && conversation.getQuestion() != null
                    && conversation.getAnswer() != null) {
                history.add(Map.of(
                        "question", conversation.getQuestion(),
                        "answer", conversation.getAnswer()));
            }
        }
        return history;
    }

    private List<String> favoriteTitles(Long userId) {
        List<String> titles = new ArrayList<>();
        for (UserFavorite favorite : favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (titles.size() >= 5) {
                break;
            }
            materialRepository.findById(favorite.getMaterialId())
                    .map(Material::getTitle)
                    .filter(Objects::nonNull)
                    .ifPresent(titles::add);
        }
        return titles;
    }

    private String phaseLabel(User.StudyPhase phase) {
        if (phase == null) {
            return null;
        }
        return switch (phase) {
            case PREPARATION -> "准备期";
            case INITIAL -> "基础期";
            case INTENSIVE -> "强化期";
            case REVIEW -> "冲刺期";
        };
    }

    private void saveConversation(Long userId, String question, String answer,
                                  AiConversation.ConvType type, Long materialId) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setQuestion(question);
        conversation.setAnswer(answer);
        conversation.setType(type);
        conversation.setMaterialId(materialId);
        conversationRepository.save(conversation);
    }
}
