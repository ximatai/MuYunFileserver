package net.ximatai.muyun.fileserver.infrastructure.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;

@ApplicationScoped
public class StorageProviderProducer {

    @Inject
    FileServiceConfig config;

    @Inject
    LocalStorageProvider localStorageProvider;

    @Inject
    MinioStorageProvider minioStorageProvider;

    @Produces
    @ApplicationScoped
    StorageProvider storageProvider() {
        if ("minio".equalsIgnoreCase(config.storage().type())) {
            return minioStorageProvider;
        }
        return localStorageProvider;
    }
}
