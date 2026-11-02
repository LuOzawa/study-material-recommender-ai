package com.recommend.controller;

import com.recommend.dto.ApiResponse;
import com.recommend.dto.MaterialDTO;
import com.recommend.entity.Material;
import com.recommend.repository.UserRepository;
import com.recommend.service.MaterialService;
import com.recommend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialService materialService;
    private final UserService userService;
    private final UserRepository userRepository;

    @GetMapping
    public ApiResponse<Page<Material>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Long categoryId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.success(materialService.list(keyword, categoryId, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Material> detail(@PathVariable Long id,
                                         @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userDetails != null
                ? userRepository.findByUsername(userDetails.getUsername()).map(u -> u.getId()).orElse(null)
                : null;
        return ApiResponse.success(materialService.getDetail(id, userId));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id,
                                                        @AuthenticationPrincipal UserDetails userDetails) throws IOException {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        MaterialService.DownloadPayload payload = materialService.prepareDownload(userId, id);

        String encodedFileName = URLEncoder.encode(payload.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName);
        if (payload.fileSize() > 0) {
            headers.setContentLength(payload.fileSize());
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(payload.stream()));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Material> create(@Valid @RequestPart("data") MaterialDTO dto,
                                         @RequestPart(value = "file", required = false) MultipartFile file,
                                         @AuthenticationPrincipal UserDetails userDetails) throws IOException {
        Long uploaderId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        return ApiResponse.success(materialService.create(dto, uploaderId, file));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Material> update(@PathVariable Long id, @RequestBody MaterialDTO dto) {
        return ApiResponse.success(materialService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        materialService.delete(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/rate")
    public ApiResponse<Map<String, Object>> rate(@PathVariable Long id,
                                                  @RequestBody Map<String, Integer> body,
                                                  @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        Integer rating = body == null ? null : body.get("rating");
        // 缺字段时直接拆箱会 NPE 并返回 500，这里改成明确的 400
        if (rating == null) {
            throw new IllegalArgumentException("请提供评分 rating（1-5）");
        }
        return ApiResponse.success(materialService.rateMaterial(userId, id, rating));
    }

    @PostMapping("/{id}/favorite")
    public ApiResponse<Map<String, Boolean>> favorite(@PathVariable Long id,
                                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        boolean added = userService.toggleFavorite(userId, id);
        return ApiResponse.success(Map.of("favorited", added));
    }

    @GetMapping("/{id}/favorite-status")
    public ApiResponse<Map<String, Boolean>> favoriteStatus(@PathVariable Long id,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userDetails != null
                ? userRepository.findByUsername(userDetails.getUsername()).map(u -> u.getId()).orElse(null)
                : null;
        boolean favorited = materialService.getFavoriteStatus(userId, id);
        return ApiResponse.success(Map.of("favorited", favorited));
    }

    @GetMapping("/{id}/like")
    public ApiResponse<Map<String, Object>> likeState(@PathVariable Long id,
                                                      @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userDetails != null
                ? userRepository.findByUsername(userDetails.getUsername()).map(u -> u.getId()).orElse(null)
                : null;
        return ApiResponse.success(materialService.getLikeState(userId, id));
    }

    @PostMapping("/{id}/like")
    public ApiResponse<Map<String, Object>> toggleLike(@PathVariable Long id,
                                                       @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        return ApiResponse.success(materialService.toggleLike(userId, id));
    }

    @GetMapping("/{id}/comments")
    public ApiResponse<List<Map<String, Object>>> comments(@PathVariable Long id) {
        return ApiResponse.success(materialService.getComments(id));
    }

    @PostMapping("/{id}/comments")
    public ApiResponse<Map<String, Object>> comment(@PathVariable Long id,
                                                    @RequestBody Map<String, String> body,
                                                    @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        return ApiResponse.success(materialService.addComment(userId, id, body.get("content")));
    }
}
