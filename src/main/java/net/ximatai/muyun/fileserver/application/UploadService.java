package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.UploadFileItemResponse;
import net.ximatai.muyun.fileserver.api.dto.UploadFilesResponse;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class UploadService {

    private static final Logger LOG = Logger.getLogger(UploadService.class);

    @Inject
    RequestContextHolder requestContextHolder;

    @Inject
    UploadRequestParser uploadRequestParser;

    @Inject
    FileMetadataRepository repository;

    @Inject
    StorageProvider storageProvider;

    @Inject
    UploadFilePreparer uploadFilePreparer;

    public UploadFilesResponse upload(MultipartFormDataInput input) {
        RequestContext requestContext = requestContextHolder.getRequired();
        UploadRequest uploadRequest = uploadRequestParser.parse(input);

        List<PreparedUpload> preparedUploads = new ArrayList<>();
        List<FileMetadata> insertedMetadata = new ArrayList<>();
        List<String> movedStorageKeys = new ArrayList<>();

        try {
            preparedUploads.addAll(uploadFilePreparer.prepare(uploadRequest, requestContext));

            List<UploadFileItemResponse> responseItems = persistUploads(
                    preparedUploads,
                    uploadRequest.remark(),
                    requestContext,
                    insertedMetadata,
                    movedStorageKeys
            );

            LOG.infof("upload success fileCount=%d tenantId=%s userId=%s requestId=%s",
                    responseItems.size(), requestContext.tenantId(), requestContext.userId(), requestContext.requestId());
            return new UploadFilesResponse(List.copyOf(responseItems));
        } catch (RuntimeException exception) {
            rollback(insertedMetadata, movedStorageKeys, preparedUploads);
            LOG.warnf("upload failed tenantId=%s userId=%s requestId=%s reason=%s",
                    requestContext.tenantId(), requestContext.userId(), requestContext.requestId(), exception.getMessage());
            throw exception;
        } finally {
            preparedUploads.forEach(item -> storageProvider.deleteTempFile(item.tempFile()));
        }
    }

    private List<UploadFileItemResponse> persistUploads(
            List<PreparedUpload> preparedUploads,
            String remark,
            RequestContext requestContext,
            List<FileMetadata> insertedMetadata,
            List<String> movedStorageKeys
    ) {
        List<UploadFileItemResponse> responseItems = new ArrayList<>();
        for (PreparedUpload preparedUpload : preparedUploads) {
            storageProvider.moveToPermanent(preparedUpload.tempFile(), preparedUpload.storageKey());
            movedStorageKeys.add(preparedUpload.storageKey());

            FileMetadata metadata = preparedUpload.toMetadata(remark, requestContext.userId(), storageProvider.providerName());
            repository.insert(metadata);
            insertedMetadata.add(metadata);

            responseItems.add(new UploadFileItemResponse(
                    metadata.id(),
                    metadata.originalFilename(),
                    metadata.mimeType(),
                    metadata.sizeBytes(),
                    metadata.uploadedAt()
            ));
        }
        return responseItems;
    }

    private void rollback(List<FileMetadata> insertedMetadata, List<String> movedStorageKeys, List<PreparedUpload> preparedUploads) {
        for (int index = insertedMetadata.size() - 1; index >= 0; index--) {
            try {
                repository.deleteById(insertedMetadata.get(index).id());
            } catch (RuntimeException exception) {
                LOG.errorf(exception, "failed to rollback metadata id=%s", insertedMetadata.get(index).id());
            }
        }

        for (int index = movedStorageKeys.size() - 1; index >= 0; index--) {
            try {
                storageProvider.deleteIfExists(movedStorageKeys.get(index));
            } catch (RuntimeException exception) {
                LOG.errorf(exception, "failed to rollback storage key=%s", movedStorageKeys.get(index));
            }
        }

        for (PreparedUpload preparedUpload : preparedUploads) {
            try {
                storageProvider.deleteTempFile(preparedUpload.tempFile());
            } catch (RuntimeException exception) {
                LOG.errorf(exception, "failed to cleanup temp upload %s", preparedUpload.tempFile());
            }
        }
    }
}
