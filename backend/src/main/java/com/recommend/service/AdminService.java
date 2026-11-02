package com.recommend.service;

import com.recommend.entity.Category;
import com.recommend.entity.Material;
import com.recommend.entity.User;
import com.recommend.event.MaterialIndexEvent;
import com.recommend.repository.CategoryRepository;
import com.recommend.repository.MaterialRepository;
import com.recommend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final MaterialRepository materialRepository;
    private final CategoryRepository categoryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final org.springframework.context.ApplicationEventPublisher publisher;

    public Page<User> listUsers(int page, int size) {
        return userRepository.findAll(PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

    @Transactional
    public User updateUser(Long id, Map<String, Object> updates) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (updates.containsKey("role")) user.setRole(User.Role.valueOf((String) updates.get("role")));
        if (updates.containsKey("enabled")) user.setEnabled((Boolean) updates.get("enabled"));
        if (updates.containsKey("email")) user.setEmail(normalizeOptionalEmail((String) updates.get("email")));
        return userRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        userRepository.findById(id).ifPresent(u -> {
            u.setEnabled(false);
            userRepository.save(u);
        });
    }

    public Page<Material> listAllMaterials(Material.Status status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        if (status != null) {
            return materialRepository.findByStatus(status, pageable);
        }
        return materialRepository.findAll(pageable);
    }

    @Transactional
    public Material reviewMaterial(Long id, String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("审核状态不能为空");
        }
        Material material = materialRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("资料不存在"));
        material.setStatus(Material.Status.valueOf(status));
        Material saved = materialRepository.save(material);
        publisher.publishEvent(new MaterialIndexEvent(saved.getId()));
        return saved;
    }

    @Transactional
    public int batchReviewMaterials(List<Long> ids, String status) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请先选择资料");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("审核状态不能为空");
        }

        Material.Status targetStatus = Material.Status.valueOf(status);
        List<Material> materials = materialRepository.findAllById(ids);
        if (materials.isEmpty()) {
            return 0;
        }

        materials.forEach(material -> material.setStatus(targetStatus));
        materialRepository.saveAll(materials);
        materials.forEach(material -> publisher.publishEvent(new MaterialIndexEvent(material.getId())));
        return materials.size();
    }

    @Transactional
    public int batchImport(List<Map<String, Object>> materials, Long adminId) {
        int count = 0;
        for (Map<String, Object> m : materials) {
            Material material = new Material();
            material.setTitle((String) m.get("title"));
            material.setDescription((String) m.get("description"));
            material.setTags((String) m.get("tags"));
            material.setAuthor((String) m.get("author"));
            material.setStatus(Material.Status.ACTIVE);
            material.setUploaderId(adminId);
            materialRepository.save(material);
            publisher.publishEvent(new MaterialIndexEvent(material.getId()));
            count++;
        }
        return count;
    }

    public List<Category> listCategories() {
        return categoryRepository.findAll(Sort.by("id").ascending());
    }

    @Transactional
    public Category createCategory(Map<String, Object> data) {
        String name = data.get("name") != null ? data.get("name").toString().trim() : "";
        if (name.isEmpty()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        Category category = new Category();
        category.setName(name);
        if (data.get("parentId") != null && !data.get("parentId").toString().isBlank()) {
            category.setParentId(Long.parseLong(data.get("parentId").toString()));
        }
        category.setDescription(data.get("description") != null ? data.get("description").toString() : null);
        return categoryRepository.save(category);
    }

    @Transactional
    public Category updateCategory(Long id, Map<String, Object> data) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分类不存在"));
        if (data.containsKey("name")) {
            String name = data.get("name") != null ? data.get("name").toString().trim() : "";
            if (name.isEmpty()) {
                throw new IllegalArgumentException("分类名称不能为空");
            }
            category.setName(name);
        }
        if (data.containsKey("parentId")) {
            if (data.get("parentId") == null || data.get("parentId").toString().isBlank()) {
                category.setParentId(null);
            } else {
                category.setParentId(Long.parseLong(data.get("parentId").toString()));
            }
        }
        if (data.containsKey("description")) {
            category.setDescription(data.get("description") != null ? data.get("description").toString() : null);
        }
        return categoryRepository.save(category);
    }

    @Transactional
    public void deleteCategory(Long id) {
        categoryRepository.deleteById(id);
    }

    private String normalizeOptionalEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public List<String> getRecentLogs(int lines) {
        int limit = Math.max(20, Math.min(lines, 500));

        List<Path> candidates = new ArrayList<>(List.of(
                Path.of("boot-run.log"),
                Path.of("boot-run.out.log"),
                Path.of("boot-run.err.log"),
                Path.of("backend", "boot-run.log"),
                Path.of("backend", "boot-run.out.log"),
                Path.of("backend", "boot-run.err.log"),
                Path.of("logs", "application.log"),
                Path.of("backend", "logs", "application.log")
        ));

        // Fallback: discover recent *.log files in project tree.
        try (var stream = Files.walk(Path.of("."), 3)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".log"))
                    .sorted(Comparator.comparingLong(this::lastModifiedMillis).reversed())
                    .forEach(candidates::add);
        } catch (Exception ignored) {
            // Keep static candidates only.
        }

        boolean hasLogFile = false;
        Path latestLogFile = null;

        for (Path path : candidates) {
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                continue;
            }
            hasLogFile = true;
            if (latestLogFile == null || lastModifiedMillis(path) >= lastModifiedMillis(latestLogFile)) {
                latestLogFile = path;
            }
            try {
                List<String> tail = readLastLines(path, limit);
                if (!tail.isEmpty()) {
                    List<String> result = new ArrayList<>(tail.size() + 1);
                    result.add("[source] " + path.toAbsolutePath());
                    result.addAll(tail);
                    return result;
                }
            } catch (Exception ignored) {
                // Try next candidate
            }
        }

        if (hasLogFile) {
            return List.of(
                    "[source] " + (latestLogFile != null ? latestLogFile.toAbsolutePath() : ""),
                    "Log file exists but is currently empty."
            );
        }

        return List.of(
                "No log file found. Start backend once to generate runtime logs.",
                "Checked common paths: boot-run*.log, backend/boot-run*.log, logs/application.log"
        );
    }

    private List<String> readLastLines(Path path, int limit) throws Exception {
        Deque<String> tail = new ArrayDeque<>(limit);
        try (var reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == limit) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        }
        return new ArrayList<>(tail);
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public Map<String, Object> exportBackupSnapshot() {
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("generatedAt", LocalDateTime.now().toString());
        backup.put("users", jdbcTemplate.queryForList("""
                SELECT id, username, email, phone, role, target_school, major, study_phase, enabled, created_at, updated_at
                FROM users
                """));
        backup.put("categories", jdbcTemplate.queryForList("SELECT id, name, parent_id, description, created_at FROM categories"));
        backup.put("materials", jdbcTemplate.queryForList("""
                SELECT id, title, category_id, tags, author, rating_avg, rating_count, view_count, download_count, status, uploader_id, created_at, updated_at
                FROM materials
                """));
        backup.put("userRatings", jdbcTemplate.queryForList("SELECT * FROM user_ratings"));
        backup.put("userFavorites", jdbcTemplate.queryForList("SELECT * FROM user_favorites"));
        backup.put("userBehaviors", jdbcTemplate.queryForList("SELECT * FROM user_behaviors"));
        backup.put("recommendations", jdbcTemplate.queryForList("SELECT * FROM recommendations"));
        return backup;
    }
}
