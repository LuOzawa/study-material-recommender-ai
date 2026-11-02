package com.recommend.service;

import com.recommend.entity.Material;
import com.recommend.event.MaterialIndexEvent;
import com.recommend.repository.CategoryRepository;
import com.recommend.repository.MaterialRepository;
import com.recommend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApplicationEventPublisher publisher;

    @InjectMocks
    private AdminService adminService;

    @Test
    void reviewMaterialChangesStatusAndPublishesEvent() {
        Material material = material(5L, Material.Status.PENDING);
        when(materialRepository.findById(5L)).thenReturn(Optional.of(material));
        when(materialRepository.save(material)).thenReturn(material);

        Material saved = adminService.reviewMaterial(5L, "ACTIVE");

        assertThat(saved.getStatus()).isEqualTo(Material.Status.ACTIVE);
        verify(publisher).publishEvent(any(MaterialIndexEvent.class));
    }

    @Test
    void batchReviewPublishesOneEventPerMaterial() {
        Material first = material(1L, Material.Status.PENDING);
        Material second = material(2L, Material.Status.PENDING);
        when(materialRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(first, second));
        when(materialRepository.saveAll(any())).thenReturn(List.of(first, second));

        int count = adminService.batchReviewMaterials(List.of(1L, 2L), "ACTIVE");

        assertThat(count).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(Material.Status.ACTIVE);
        assertThat(second.getStatus()).isEqualTo(Material.Status.ACTIVE);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(any(MaterialIndexEvent.class));
    }

    @Test
    void batchReviewRejectsEmptySelection() {
        assertThatThrownBy(() -> adminService.batchReviewMaterials(List.of(), "ACTIVE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请先选择资料");
    }

    @Test
    void batchImportMarksMaterialsActiveAndAttributesUploader() {
        when(materialRepository.save(any(Material.class))).thenAnswer(invocation -> {
            Material material = invocation.getArgument(0);
            material.setId(9L);
            return material;
        });

        int count = adminService.batchImport(
                List.of(Map.of("title", "政治冲刺", "tags", "政治")), 3L);

        assertThat(count).isEqualTo(1);
        verify(materialRepository).save(any(Material.class));
        verify(publisher).publishEvent(any(MaterialIndexEvent.class));
    }

    private Material material(Long id, Material.Status status) {
        Material material = new Material();
        material.setId(id);
        material.setStatus(status);
        return material;
    }
}
