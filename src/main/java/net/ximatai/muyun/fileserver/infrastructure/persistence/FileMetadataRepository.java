package net.ximatai.muyun.fileserver.infrastructure.persistence;

import net.ximatai.muyun.fileserver.domain.file.FileMetadata;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FileMetadataRepository {

    boolean existsById(String fileId);

    void insert(FileMetadata metadata);

    Optional<FileMetadata> findById(String fileId);

    Optional<FileMetadata> findActiveById(String fileId);

    boolean softDelete(String fileId, String tenantId, String deletedBy, Instant deletedAt);

    List<FileMetadata> findDeletedBefore(Instant cutoff, int limit);

    void deleteById(String fileId);
}
