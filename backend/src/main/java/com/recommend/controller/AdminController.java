package com.recommend.controller;

import com.recommend.dto.ApiResponse;
import com.recommend.entity.Category;
import com.recommend.entity.Material;
import com.recommend.entity.User;
import com.recommend.repository.UserRepository;
import com.recommend.service.AdminService;
import com.recommend.service.HadoopBenchmarkService;
import com.recommend.service.HadoopDatasetExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final HadoopDatasetExportService hadoopDatasetExportService;
    private final HadoopBenchmarkService hadoopBenchmarkService;
    private final UserRepository userRepository;

    @GetMapping("/users")
    public ApiResponse<Page<User>> listUsers(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        Page<User> users = adminService.listUsers(page, size);
        users.forEach(u -> u.setPassword(null));
        return ApiResponse.success(users);
    }

    @PutMapping("/users/{id}")
    public ApiResponse<User> updateUser(@PathVariable Long id,
                                         @RequestBody Map<String, Object> updates) {
        User user = adminService.updateUser(id, updates);
        user.setPassword(null);
        return ApiResponse.success(user);
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        adminService.deleteUser(id);
        return ApiResponse.success();
    }

    @GetMapping("/materials")
    public ApiResponse<Page<Material>> listMaterials(@RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        Material.Status statusEnum = status != null ? Material.Status.valueOf(status) : null;
        return ApiResponse.success(adminService.listAllMaterials(statusEnum, page, size));
    }

    @PutMapping("/materials/{id}/review")
    public ApiResponse<Material> reviewMaterial(@PathVariable Long id,
                                                 @RequestBody Map<String, String> body) {
        return ApiResponse.success(adminService.reviewMaterial(id, body.get("status")));
    }

    @PutMapping("/materials/review/batch")
    public ApiResponse<Map<String, Integer>> batchReviewMaterials(@RequestBody Map<String, Object> body) {
        List<Long> ids = ((List<?>) body.getOrDefault("ids", List.of())).stream()
                .filter(Objects::nonNull)
                .map(item -> Long.parseLong(item.toString()))
                .distinct()
                .toList();
        String status = body.get("status") != null ? body.get("status").toString() : null;
        int updated = adminService.batchReviewMaterials(ids, status);
        return ApiResponse.success(Map.of("updated", updated));
    }

    @PostMapping("/materials/batch")
    public ApiResponse<Map<String, Integer>> batchImport(@RequestBody List<Map<String, Object>> materials,
                                                          @AuthenticationPrincipal UserDetails userDetails) {
        Long adminId = userRepository.findByUsername(userDetails.getUsername()).orElseThrow().getId();
        int count = adminService.batchImport(materials, adminId);
        return ApiResponse.success(Map.of("imported", count));
    }

    @GetMapping("/categories")
    public ApiResponse<List<Category>> listCategories() {
        return ApiResponse.success(adminService.listCategories());
    }

    @PostMapping("/categories")
    public ApiResponse<Category> createCategory(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(adminService.createCategory(body));
    }

    @PutMapping("/categories/{id}")
    public ApiResponse<Category> updateCategory(@PathVariable Long id,
                                                @RequestBody Map<String, Object> body) {
        return ApiResponse.success(adminService.updateCategory(id, body));
    }

    @DeleteMapping("/categories/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        adminService.deleteCategory(id);
        return ApiResponse.success();
    }

    @GetMapping("/logs")
    public ApiResponse<List<String>> getLogs(@RequestParam(defaultValue = "200") int lines) {
        return ApiResponse.success(adminService.getRecentLogs(lines));
    }

    @GetMapping("/backup")
    public ApiResponse<Map<String, Object>> backup() {
        return ApiResponse.success(adminService.exportBackupSnapshot());
    }

    @PostMapping("/hadoop/export")
    public ApiResponse<Map<String, Object>> exportHadoopDatasets() throws Exception {
        return ApiResponse.success(hadoopDatasetExportService.exportNow("manual"));
    }

    @GetMapping("/hadoop/export/latest")
    public ApiResponse<Map<String, Object>> getLatestHadoopExport() throws Exception {
        return ApiResponse.success(hadoopDatasetExportService.getLatestExportSummary());
    }

    /**
     * 资料热度计算对比：MySQL 单机聚合 vs Hadoop MapReduce（本地模式）。
     *
     * <p>两条链路用同一份导出数据、同一套行为权重，输出 TopN 排行榜与耗时，
     * 并校验结果是否一致。用于论文里讨论「什么规模下才需要上 Hadoop」。</p>
     *
     * <p>调用前需要先执行 {@code POST /api/admin/hadoop/export} 导出数据。</p>
     */
    @PostMapping("/hadoop/benchmark")
    public ApiResponse<Map<String, Object>> benchmarkHotness(
            @RequestParam(defaultValue = "20") int topN) throws Exception {
        return ApiResponse.success(hadoopBenchmarkService.benchmark(topN));
    }
}
