package net.ximatai.muyun.fileserver.domain.preview;

import java.time.Instant;

public record FilePreviewArtifact(
        String fileId,
        String artifactKey,
        String tenantId,
        PreviewSourceKind sourceKind,
        PreviewArtifactStatus status,
        String targetMimeType,
        String storageProvider,
        String storageBucket,
        String storageKey,
        Long sizeBytes,
        String sha256,
        Instant generatedAt,
        Instant lastAccessedAt,
        PreviewFailureCode failureCode,
        String failureMessage
) {
}
