package net.ximatai.muyun.fileserver.job;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import org.jboss.logging.Logger;

@ApplicationScoped
public class FileCleanupJob {

    private static final Logger LOG = Logger.getLogger(FileCleanupJob.class);

    @Inject
    FileServiceConfig config;

    @Inject
    FileMetadataRepository fileMetadataRepository;

    @Scheduled(every = "${mfs.cleanup.deleted-sweep-interval}")
    void sweepDeletedFiles() {
        LOG.infof("deleted file cleanup tick started: retention=%s, batchSize=%d",
                config.cleanup().deletedRetention(),
                config.cleanup().batchSize());
        // Actual physical cleanup will be implemented with the upload/delete flow.
        fileMetadataRepository.findDeletedBefore(
                java.time.Instant.now().minus(config.cleanup().deletedRetention()),
                config.cleanup().batchSize()
        );
    }
}
