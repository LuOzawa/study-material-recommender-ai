package com.recommend.controller;

import com.recommend.dto.AiChatRequest;
import com.recommend.dto.AiDtos;
import com.recommend.dto.ApiResponse;
import com.recommend.entity.AiConversation;
import com.recommend.repository.UserRepository;
import com.recommend.service.AiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final UserRepository userRepository;

    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(@Valid @RequestBody AiChatRequest request,
                                                 @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserId(userDetails);
        AiDtos.ChatResult result = aiService.chat(userId, request.getQuestion());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answer", result.answer());
        body.put("citations", Optional.ofNullable(result.citations()).orElse(List.of()));
        body.put("references", Optional.ofNullable(result.references()).orElse(List.of()));
        body.put("route", result.route());
        body.put("model", result.model());
        return ApiResponse.success(body);
    }

    @PostMapping("/summarize/{materialId}")
    public ApiResponse<Map<String, Object>> summarize(@PathVariable Long materialId,
                                                      @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserId(userDetails);
        AiDtos.SummaryResult result = aiService.summarize(userId, materialId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", result.summary());
        body.put("citations", Optional.ofNullable(result.citations()).orElse(List.of()));
        body.put("grounded", result.grounded());
        body.put("chunks", result.chunks());
        body.put("model", result.model());
        return ApiResponse.success(body);
    }

    @PostMapping("/suggestions")
    public ApiResponse<Map<String, Object>> suggestions(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserId(userDetails);
        AiDtos.SuggestionResult result = aiService.getSuggestions(userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("suggestions", result.suggestions());
        body.put("references", Optional.ofNullable(result.references()).orElse(List.of()));
        body.put("model", result.model());
        return ApiResponse.success(body);
    }

    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> semanticSearch(@RequestParam String q,
                                                           @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserId(userDetails);
        return ApiResponse.success(aiService.semanticSearch(userId, q));
    }

    @GetMapping("/history")
    public ApiResponse<List<AiConversation>> getHistory(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserId(userDetails);
        return ApiResponse.success(aiService.getHistory(userId));
    }

    private Long currentUserId(UserDetails userDetails) {
        return userRepository.findByUsernameIgnoreCase(userDetails.getUsername()).orElseThrow().getId();
    }
}
