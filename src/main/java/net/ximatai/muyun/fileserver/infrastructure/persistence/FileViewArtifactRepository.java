package net.ximatai.muyun.fileserver.infrastructure.persistence;

import net.ximatai.muyun.fileserver.domain.view.FileViewArtifact;

import java.time.Instant;
import java.util.Optional;

public interface FileViewArtifactRepository {

    Optional<FileViewArtifact> findByFileIdAndArtifactKey(String fileId, String artifactKey);

    void save(FileViewArtifact artifact);

    void touchAccessedAt(String fileId, String artifactKey, Instant accessedAt);

    void deleteByFileId(String fileId);
}
