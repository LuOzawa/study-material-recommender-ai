package com.recommend.controller;

import com.recommend.client.AiServiceClient;
import com.recommend.dto.AiDtos;
import com.recommend.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin operations for the vector index (path is ADMIN-only in SecurityConfig). */
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
public class AiAdminController {

    private final AiServiceClient aiServiceClient;

    @PostMapping("/reindex")
    public ApiResponse<AiDtos.RebuildResult> reindex() {
        return ApiResponse.success(aiServiceClient.rebuild());
    }

    @GetMapping("/stats")
    public ApiResponse<AiDtos.IndexStats> stats() {
        return ApiResponse.success(aiServiceClient.stats());
    }
}
