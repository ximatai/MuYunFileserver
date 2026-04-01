package net.ximatai.muyun.fileserver.infrastructure.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import net.ximatai.muyun.fileserver.common.exception.StorageException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@ApplicationScoped
public class LocalStorageProvider implements StorageProvider {

    private static final String STORAGE_PROVIDER = "local";

    @Inject
    FileServiceConfig config;

    public void onStart(@Observes StartupEvent ignored) {
        try {
            Files.createDirectories(config.storage().rootDir());
            Files.createDirectories(config.storage().tempDir());
        } catch (IOException exception) {
            throw new IllegalStateException("failed to initialize storage directories", exception);
        }
    }

    @Override
    public String providerName() {
        return STORAGE_PROVIDER;
    }

    @Override
    public Path createTempFile() {
        try {
            return Files.createTempFile(config.storage().tempDir(), "upload-", ".tmp");
        } catch (IOException exception) {
            throw new StorageException("failed to create temp file", exception);
        }
    }

    @Override
    public void moveToPermanent(Path tempFile, String storageKey) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new StorageException("failed to move file to permanent storage", exception);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            return Files.newInputStream(resolve(storageKey));
        } catch (IOException exception) {
            throw new StorageException("failed to open stored file", exception);
        }
    }

    @Override
    public void deleteIfExists(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw new StorageException("failed to delete stored file", exception);
        }
    }

    @Override
    public void deleteTempFile(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException exception) {
            throw new StorageException("failed to delete temp file", exception);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.exists(resolve(storageKey));
    }

    @Override
    public Path resolve(String storageKey) {
        return config.storage().rootDir().resolve(storageKey);
    }
}
