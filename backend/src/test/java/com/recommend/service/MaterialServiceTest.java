package com.recommend.service;

import com.recommend.dto.MaterialDTO;
import com.recommend.entity.Material;
import com.recommend.event.MaterialIndexEvent;
import com.recommend.repository.CategoryRepository;
import com.recommend.repository.MaterialCommentRepository;
import com.recommend.repository.MaterialLikeRepository;
import com.recommend.repository.MaterialRepository;
import com.recommend.repository.UserBehaviorRepository;
import com.recommend.repository.UserFavoriteRepository;
import com.recommend.repository.UserRatingRepository;
import com.recommend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialServiceTest {

    @Mock private MaterialRepository materialRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private UserBehaviorRepository behaviorRepository;
    @Mock private UserRatingRepository ratingRepository;
    @Mock private UserFavoriteRepository favoriteRepository;
    @Mock private MaterialLikeRepository likeRepository;
    @Mock private MaterialCommentRepository commentRepository;
    @Mock private UserRepository userRepository;
    @Mock private HdfsService hdfsService;
    @Mock private ApplicationEventPublisher publisher;

    @InjectMocks
    private MaterialService materialService;

    @Test
    void deleteMarksMaterialDeletedAndPublishesIndexEvent() {
        Material material = material(5L, Material.Status.ACTIVE);
        when(materialRepository.findById(5L)).thenReturn(Optional.of(material));
        when(materialRepository.save(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));

        materialService.delete(5L);

        assertThat(material.getStatus()).isEqualTo(Material.Status.DELETED);
        verify(publisher).publishEvent(any(MaterialIndexEvent.class));
    }

    @Test
    void updateAppliesOnlyProvidedFieldsAndPublishes() {
        Material material = material(5L, Material.Status.ACTIVE);
        material.setTitle("old");
        material.setTags("old-tag");
        when(materialRepository.findById(5L)).thenReturn(Optional.of(material));
        when(materialRepository.save(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MaterialDTO dto = new MaterialDTO();
        dto.setTitle("new title");
        dto.setTags("数学,线代");

        Material updated = materialService.update(5L, dto);

        assertThat(updated.getTitle()).isEqualTo("new title");
        assertThat(updated.getTags()).isEqualTo("数学,线代");
        verify(publisher).publishEvent(any(MaterialIndexEvent.class));
    }

    @Test
    void getDetailRejectsNonActiveMaterial() {
        when(materialRepository.findById(9L)).thenReturn(Optional.of(material(9L, Material.Status.PENDING)));

        assertThatThrownBy(() -> materialService.getDetail(9L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("资料不存在或已下架");
    }

    @Test
    void addCommentWritesCommentBehavior() {
        Material material = material(1L, Material.Status.ACTIVE);
        when(materialRepository.findById(1L)).thenReturn(Optional.of(material));
        when(commentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        com.recommend.entity.User user = new com.recommend.entity.User();
        user.setId(2L);
        user.setUsername("alice");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        materialService.addComment(2L, 1L, "这条资料很有用");

        org.mockito.ArgumentCaptor<com.recommend.entity.UserBehavior> captor =
                org.mockito.ArgumentCaptor.forClass(com.recommend.entity.UserBehavior.class);
        verify(behaviorRepository).save(captor.capture());
        assertThat(captor.getValue().getAction())
                .isEqualTo(com.recommend.entity.UserBehavior.Action.COMMENT);
        assertThat(captor.getValue().getMaterialId()).isEqualTo(1L);
    }

    private Material material(Long id, Material.Status status) {
        Material material = new Material();
        material.setId(id);
        material.setStatus(status);
        return material;
    }
}
