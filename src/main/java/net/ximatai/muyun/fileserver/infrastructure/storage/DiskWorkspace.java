package net.ximatai.muyun.fileserver.infrastructure.storage;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.application.storage.LocalWorkspace;
import net.ximatai.muyun.fileserver.common.exception.StorageException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class DiskWorkspace implements LocalWorkspace {
    @Inject FileServiceConfig config;

    void initialize(@Observes StartupEvent ignored, StorageLocations locations) {
        locations.validateWorkspace();
        Path root = config.storage().tempDir();
        try {
            Files.createDirectories(root);
            try (var paths = Files.newDirectoryStream(root, "upload-*.tmp")) {
                for (Path path : paths) {
                    if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        Files.delete(path);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to initialize storage workspace", e);
        }
    }

    public void verifyReadiness() {
        Path root = config.storage().tempDir();
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw new StorageException("temp directory is not writable");
        }
    }

    @Override
    public Path createTempFile() {
        try {
            return Files.createTempFile(config.storage().tempDir(), "upload-", ".tmp");
        } catch (IOException e) {
            throw new StorageException("failed to create temp file", e);
        }
    }

    @Override
    public void deleteTempFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new StorageException("failed to delete temp file", e);
        }
    }
}
