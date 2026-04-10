package net.ximatai.muyun.fileserver.infrastructure.persistence;

import net.ximatai.muyun.fileserver.domain.preview.FilePreviewArtifact;

import java.time.Instant;
import java.util.Optional;

public interface FilePreviewArtifactRepository {

    Optional<FilePreviewArtifact> findByFileIdAndArtifactKey(String fileId, String artifactKey);

    void save(FilePreviewArtifact artifact);

    void touchAccessedAt(String fileId, String artifactKey, Instant accessedAt);

    void deleteByFileId(String fileId);
}
