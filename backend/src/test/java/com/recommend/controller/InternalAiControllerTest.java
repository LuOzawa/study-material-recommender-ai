package com.recommend.controller;

import com.recommend.config.AiServiceProperties;
import com.recommend.dto.AiDtos;
import com.recommend.entity.Category;
import com.recommend.entity.Material;
import com.recommend.repository.MaterialRepository;
import com.recommend.security.InternalTokenVerifier;
import com.recommend.service.AiMaterialSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalAiControllerTest {

    private MaterialRepository materialRepository;
    private AiMaterialSnapshotService snapshotService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AiServiceProperties properties = new AiServiceProperties();
        properties.setInternalToken("secret-token");

        materialRepository = mock(MaterialRepository.class);
        snapshotService = mock(AiMaterialSnapshotService.class);
        InternalAiController controller = new InternalAiController(
                materialRepository, snapshotService, new InternalTokenVerifier(properties));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        mockMvc.perform(get("/api/internal/ai/materials"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/internal/ai/materials").header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsPagedSnapshotsWithSnakeCaseFields() throws Exception {
        Material material = new Material();
        material.setId(4L);
        material.setTitle("数学一真题");
        material.setCategoryId(2L);
        material.setStatus(Material.Status.ACTIVE);
        material.setUpdatedAt(LocalDateTime.of(2026, 10, 1, 8, 0));

        when(materialRepository.findByStatus(eq(Material.Status.ACTIVE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(material), PageRequest.of(0, 2), 3));
        when(snapshotService.categoryNames()).thenReturn(Map.of(2L, "数学"));
        when(snapshotService.snapshot(material, "数学")).thenReturn(new AiDtos.MaterialPayload(
                4L, "数学一真题", "d", "数学", 2L, "数学", "ACTIVE", "研途", 4.5, "2026-10-01T08:00:00"));

        mockMvc.perform(get("/api/internal/ai/materials")
                        .header("X-Internal-Token", "secret-token")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("数学一真题"))
                .andExpect(jsonPath("$.items[0].category_name").value("数学"))
                .andExpect(jsonPath("$.items[0].updated_at").value("2026-10-01T08:00:00"))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.total").value(3));
    }
}
