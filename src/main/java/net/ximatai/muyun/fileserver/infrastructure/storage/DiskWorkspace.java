package net.ximatai.muyun.fileserver.infrastructure.storage;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.application.storage.LocalWorkspace;
import net.ximatai.muyun.fileserver.common.exception.StorageException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@ApplicationScoped
public class DiskWorkspace implements LocalWorkspace {
    private static final Logger LOG = Logger.getLogger(DiskWorkspace.class);
    private static final String PDF_DIRECTORY_PREFIX = "rendered-pdf-work-";

    @Inject FileServiceConfig config;

    void initialize(@Observes StartupEvent ignored) {
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
            try (var paths = Files.newDirectoryStream(root, PDF_DIRECTORY_PREFIX + "*")) {
                for (Path path : paths) {
                    deletePdfWorkDirectory(path);
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

    @Override
    public Path createPdfWorkDirectory() {
        try {
            return Files.createTempDirectory(config.storage().tempDir(), PDF_DIRECTORY_PREFIX);
        } catch (IOException e) {
            throw new StorageException("failed to create PDF workspace", e);
        }
    }

    @Override
    public void deletePdfWorkDirectory(Path directory) {
        Path path = directory.toAbsolutePath().normalize();
        if (!config.storage().tempDir().toAbsolutePath().normalize().equals(path.getParent())
                || !path.getFileName().toString().startsWith(PDF_DIRECTORY_PREFIX)
                || !Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            LOG.warn("failed to remove PDF workspace " + path, e);
        }
    }
}
