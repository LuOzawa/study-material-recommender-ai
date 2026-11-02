package com.recommend.service;

import com.recommend.client.AiServiceClient;
import com.recommend.client.AiServiceException;
import com.recommend.dto.AiDtos;
import com.recommend.entity.Material;
import com.recommend.event.MaterialIndexEvent;
import com.recommend.repository.MaterialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiIndexSyncServiceTest {

    private AiServiceClient client;
    private MaterialRepository materialRepository;
    private AiMaterialSnapshotService snapshotService;
    private AiIndexSyncService service;

    @BeforeEach
    void setUp() {
        client = mock(AiServiceClient.class);
        materialRepository = mock(MaterialRepository.class);
        snapshotService = mock(AiMaterialSnapshotService.class);
        service = new AiIndexSyncService(client, materialRepository, snapshotService);
    }

    @Test
    void activeMaterialIsPushedToIndex() {
        Material material = material(1L, Material.Status.ACTIVE);
        AiDtos.MaterialPayload payload = new AiDtos.MaterialPayload(
                1L, "t", "d", "tag", 1L, "数学", "ACTIVE", "a", 4.0, "2026-10-01T08:00:00");

        when(client.isConfigured()).thenReturn(true);
        when(materialRepository.findById(1L)).thenReturn(Optional.of(material));
        when(snapshotService.snapshot(material)).thenReturn(payload);

        service.onMaterialChanged(new MaterialIndexEvent(1L));

        verify(client).indexMaterial(payload);
        verify(client, never()).deleteMaterial(anyLong());
    }

    @Test
    void deletedOrPendingMaterialIsRemovedFromIndex() {
        when(client.isConfigured()).thenReturn(true);
        when(materialRepository.findById(1L)).thenReturn(Optional.of(material(1L, Material.Status.DELETED)));

        service.onMaterialChanged(new MaterialIndexEvent(1L));

        verify(client).deleteMaterial(1L);
        verify(client, never()).indexMaterial(any());
    }

    @Test
    void missingMaterialIsRemovedFromIndex() {
        when(client.isConfigured()).thenReturn(true);
        when(materialRepository.findById(9L)).thenReturn(Optional.empty());

        service.onMaterialChanged(new MaterialIndexEvent(9L));

        verify(client).deleteMaterial(9L);
    }

    @Test
    void unconfiguredClientSkipsWork() {
        when(client.isConfigured()).thenReturn(false);

        service.onMaterialChanged(new MaterialIndexEvent(1L));

        verifyNoInteractions(materialRepository);
    }

    @Test
    void indexingFailureNeverPropagates() {
        Material material = material(1L, Material.Status.ACTIVE);
        when(client.isConfigured()).thenReturn(true);
        when(materialRepository.findById(1L)).thenReturn(Optional.of(material));
        when(snapshotService.snapshot(material)).thenReturn(mock(AiDtos.MaterialPayload.class));
        doThrow(new AiServiceException("down")).when(client).indexMaterial(any());

        assertDoesNotThrow(() -> service.onMaterialChanged(new MaterialIndexEvent(1L)));
    }

    private Material material(Long id, Material.Status status) {
        Material material = new Material();
        material.setId(id);
        material.setTitle("t");
        material.setStatus(status);
        return material;
    }
}
