package net.ximatai.muyun.fileserver.domain.view;

import java.time.Instant;

public record FileViewArtifact(
        String fileId,
        String artifactKey,
        String tenantId,
        ViewArtifactSourceKind sourceKind,
        ViewArtifactStatus status,
        String targetMimeType,
        String storageProvider,
        String storageBucket,
        String storageKey,
        Long sizeBytes,
        String sha256,
        Instant generatedAt,
        Instant lastAccessedAt,
        ViewArtifactFailureCode failureCode,
        String failureMessage
) {
}
