package net.ximatai.muyun.fileserver.job;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.common.log.OperationLog;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.application.PreviewService;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class FileCleanupJob {

    private static final Logger LOG = Logger.getLogger(FileCleanupJob.class);
    private static final String TEMPORARY_CLEANUP_ACTOR = "system:temporary-cleanup";

    @Inject
    FileServiceConfig config;

    @Inject
    FileMetadataRepository fileMetadataRepository;

    @Inject
    StorageProvider storageProvider;

    @Inject
    PreviewService previewService;

    @Scheduled(every = "${mfs.cleanup.deleted-sweep-interval}")
    void sweepDeletedFiles() {
        sweepNow();
    }

    public void sweepNow() {
        sweepTemporaryFiles();
        sweepDeletedFilesInternal();
    }

    private void sweepTemporaryFiles() {
        Instant cutoff = Instant.now().minus(config.cleanup().temporaryRetention());
        List<FileMetadata> items = fileMetadataRepository.findTemporaryBefore(cutoff, config.cleanup().batchSize());
        LOG.info(OperationLog.format(
                "temporary_file_cleanup",
                "started",
                "retention", config.cleanup().temporaryRetention().toString(),
                "batch_size", String.valueOf(config.cleanup().batchSize()),
                "storage_provider", storageProvider.providerName()
        ));
        for (FileMetadata item : items) {
            try {
                if (!fileMetadataRepository.markTemporaryForCleanup(item.id(), Instant.now(), TEMPORARY_CLEANUP_ACTOR)) {
                    continue;
                }
                previewService.deletePreviewIfExists(item);
                storageProvider.deleteIfExists(item.storageKey());
                fileMetadataRepository.deleteById(item.id());
                LOG.info(OperationLog.format(
                        "temporary_file_cleanup",
                        "success",
                        "file_id", item.id(),
                        "tenant_id", item.tenantId(),
                        "storage_provider", item.storageProvider()
                ));
            } catch (RuntimeException exception) {
                LOG.error(OperationLog.format(
                        "temporary_file_cleanup",
                        "failure",
                        "file_id", item.id(),
                        "tenant_id", item.tenantId(),
                        "storage_provider", item.storageProvider(),
                        "reason", exception.getMessage()
                ), exception);
            }
        }
    }

    private void sweepDeletedFilesInternal() {
        Instant cutoff = Instant.now().minus(config.cleanup().deletedRetention());
        List<FileMetadata> items = fileMetadataRepository.findDeletedBefore(cutoff, config.cleanup().batchSize());
        LOG.info(OperationLog.format(
                "file_cleanup",
                "started",
                "retention", config.cleanup().deletedRetention().toString(),
                "batch_size", String.valueOf(config.cleanup().batchSize()),
                "storage_provider", storageProvider.providerName()
        ));
        for (FileMetadata item : items) {
            try {
                previewService.deletePreviewIfExists(item);
                storageProvider.deleteIfExists(item.storageKey());
                fileMetadataRepository.deleteById(item.id());
                LOG.info(OperationLog.format(
                        "file_cleanup",
                        "success",
                        "file_id", item.id(),
                        "tenant_id", item.tenantId(),
                        "storage_provider", item.storageProvider()
                ));
            } catch (RuntimeException exception) {
                LOG.error(OperationLog.format(
                        "file_cleanup",
                        "failure",
                        "file_id", item.id(),
                        "tenant_id", item.tenantId(),
                        "storage_provider", item.storageProvider(),
                        "reason", exception.getMessage()
                ), exception);
            }
        }
    }
}
