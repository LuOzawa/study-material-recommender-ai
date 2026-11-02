package com.recommend.service;

import com.recommend.client.AiServiceClient;
import com.recommend.client.AiServiceException;
import com.recommend.entity.Material;
import com.recommend.event.MaterialIndexEvent;
import com.recommend.repository.MaterialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mirrors material changes into the pgvector index after the MySQL transaction
 * commits.  Failures are logged only: indexing must never break moderation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiIndexSyncService {

    private final AiServiceClient aiServiceClient;
    private final MaterialRepository materialRepository;
    private final AiMaterialSnapshotService snapshotService;

    @Async("aiIndexExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMaterialChanged(MaterialIndexEvent event) {
        if (!aiServiceClient.isConfigured()) {
            return;
        }
        Long materialId = event.materialId();
        try {
            Material material = materialRepository.findById(materialId).orElse(null);
            if (material == null || material.getStatus() != Material.Status.ACTIVE) {
                aiServiceClient.deleteMaterial(materialId);
                return;
            }
            aiServiceClient.indexMaterial(snapshotService.snapshot(material));
        } catch (AiServiceException e) {
            log.warn("同步资料 {} 到向量索引失败：{}", materialId, e.getMessage());
        } catch (Exception e) {
            log.warn("同步资料 {} 到向量索引异常", materialId, e);
        }
    }
}
