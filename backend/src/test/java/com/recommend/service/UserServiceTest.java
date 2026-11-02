package com.recommend.service;

import com.recommend.entity.User;
import com.recommend.entity.UserBehavior;
import com.recommend.repository.MaterialRepository;
import com.recommend.repository.UserBehaviorRepository;
import com.recommend.repository.UserFavoriteRepository;
import com.recommend.repository.UserRatingRepository;
import com.recommend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserFavoriteRepository favoriteRepository;
    @Mock private UserBehaviorRepository behaviorRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private UserRatingRepository ratingRepository;
    @Mock private UserPreferenceService preferenceService;

    @InjectMocks
    private UserService userService;

    @Test
    void toggleFavoriteAddsFavoriteAndCollectBehavior() {
        when(favoriteRepository.existsByUserIdAndMaterialId(2L, 1L)).thenReturn(false);

        boolean added = userService.toggleFavorite(2L, 1L);

        assertThat(added).isTrue();
        verify(favoriteRepository).save(any());
        ArgumentCaptor<UserBehavior> captor = ArgumentCaptor.forClass(UserBehavior.class);
        verify(behaviorRepository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(UserBehavior.Action.COLLECT);
    }

    @Test
    void toggleFavoriteRemovesWithoutBehavior() {
        when(favoriteRepository.existsByUserIdAndMaterialId(2L, 1L)).thenReturn(true);

        boolean added = userService.toggleFavorite(2L, 1L);

        assertThat(added).isFalse();
        verify(favoriteRepository).deleteByUserIdAndMaterialId(2L, 1L);
        verify(behaviorRepository, never()).save(any());
    }

    @Test
    void updateProfileOnlyChangesProvidedFields() {
        User user = new User();
        user.setId(2L);
        user.setUsername("alice");
        user.setMajor("old-major");
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        User updated = userService.updateProfile("alice", Map.of(
                "targetSchool", "中山大学", "studyPhase", "INTENSIVE"));

        assertThat(updated.getTargetSchool()).isEqualTo("中山大学");
        assertThat(updated.getStudyPhase()).isEqualTo(User.StudyPhase.INTENSIVE);
        assertThat(updated.getMajor()).isEqualTo("old-major");
    }
}
