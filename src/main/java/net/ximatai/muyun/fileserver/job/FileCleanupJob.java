package net.ximatai.muyun.fileserver.job;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class FileCleanupJob {

    private static final Logger LOG = Logger.getLogger(FileCleanupJob.class);

    @Inject
    FileServiceConfig config;

    @Inject
    FileMetadataRepository fileMetadataRepository;

    @Inject
    StorageProvider storageProvider;

    @Scheduled(every = "${mfs.cleanup.deleted-sweep-interval}")
    void sweepDeletedFiles() {
        Instant cutoff = Instant.now().minus(config.cleanup().deletedRetention());
        List<FileMetadata> items = fileMetadataRepository.findDeletedBefore(cutoff, config.cleanup().batchSize());
        LOG.infof("deleted file cleanup tick started: retention=%s, batchSize=%d",
                config.cleanup().deletedRetention(),
                config.cleanup().batchSize());
        for (FileMetadata item : items) {
            try {
                storageProvider.deleteIfExists(item.storageKey());
                fileMetadataRepository.deleteById(item.id());
                LOG.infof("deleted file cleanup success fileId=%s tenantId=%s", item.id(), item.tenantId());
            } catch (RuntimeException exception) {
                LOG.errorf(exception, "deleted file cleanup failed fileId=%s tenantId=%s", item.id(), item.tenantId());
            }
        }
    }
}
