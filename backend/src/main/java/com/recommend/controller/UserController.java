package com.recommend.controller;

import com.recommend.dto.ApiResponse;
import com.recommend.entity.Material;
import com.recommend.entity.User;
import com.recommend.repository.UserRepository;
import com.recommend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;

    @GetMapping("/profile")
    public ApiResponse<User> getProfile(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userService.getProfile(userDetails.getUsername());
        user.setPassword(null); // never return password
        return ApiResponse.success(user);
    }

    @PutMapping("/profile")
    public ApiResponse<User> updateProfile(@AuthenticationPrincipal UserDetails userDetails,
                                           @RequestBody Map<String, String> updates) {
        User user = userService.updateProfile(userDetails.getUsername(), updates);
        user.setPassword(null);
        return ApiResponse.success(user);
    }

    @GetMapping("/favorites")
    public ApiResponse<List<Material>> getFavorites(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        return ApiResponse.success(userService.getFavorites(userId));
    }

    @PostMapping("/favorites/{materialId}")
    public ApiResponse<Map<String, Boolean>> toggleFavorite(@AuthenticationPrincipal UserDetails userDetails,
                                                             @PathVariable Long materialId) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        boolean added = userService.toggleFavorite(userId, materialId);
        return ApiResponse.success(Map.of("favorited", added));
    }

    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> getHistory(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        return ApiResponse.success(userService.getHistoryView(userId));
    }

    @DeleteMapping("/history")
    public ApiResponse<Void> clearHistory(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        userService.clearHistory(userId);
        return ApiResponse.success();
    }

    @DeleteMapping("/history/{historyId}")
    public ApiResponse<Void> deleteHistoryItem(@AuthenticationPrincipal UserDetails userDetails,
                                               @PathVariable Long historyId) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        userService.deleteHistoryItem(userId, historyId);
        return ApiResponse.success();
    }

    @GetMapping("/ratings")
    public ApiResponse<List<Map<String, Object>>> getRatings(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        return ApiResponse.success(userService.getRatings(userId));
    }

    @DeleteMapping("/ratings/{materialId}")
    public ApiResponse<Void> deleteRating(@AuthenticationPrincipal UserDetails userDetails,
                                          @PathVariable Long materialId) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        userService.deleteRating(userId, materialId);
        return ApiResponse.success();
    }

    @GetMapping("/preferences")
    public ApiResponse<Map<String, Object>> getPreferences(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        return ApiResponse.success(userService.getPreferenceView(userId));
    }

    @PutMapping("/preferences")
    public ApiResponse<Map<String, Object>> updatePreferences(@AuthenticationPrincipal UserDetails userDetails,
                                                              @RequestBody Map<String, Object> updates) {
        Long userId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        return ApiResponse.success(userService.updatePreferenceView(userId, updates));
    }
}
