package net.ximatai.muyun.fileserver.infrastructure.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.common.exception.StorageException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@ApplicationScoped
@Typed(MinioStorageProvider.class)
public class MinioStorageProvider implements StorageProvider {

    private static final String STORAGE_PROVIDER = "minio";

    @Inject
    FileServiceConfig config;

    private volatile MinioClient client;

    public void onStart(@Observes StartupEvent ignored) {
        if (!isEnabled()) {
            return;
        }
        try {
            Files.createDirectories(config.storage().tempDir());
            cleanupTempDirectory();
            if (config.storage().minio().autoCreateBucket() && !bucketExists()) {
                client().makeBucket(MakeBucketArgs.builder().bucket(bucket()).build());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to initialize minio temp directory", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to initialize minio storage", exception);
        }
    }

    @Override
    public String providerName() {
        return STORAGE_PROVIDER;
    }

    @Override
    public String storageBucket() {
        return bucket();
    }

    @Override
    public void verifyReadiness() {
        try {
            if (!Files.isDirectory(config.storage().tempDir()) || !Files.isWritable(config.storage().tempDir())) {
                throw new StorageException("temp directory is not writable");
            }
            if (!bucketExists()) {
                throw new StorageException("minio bucket is not accessible");
            }
        } catch (StorageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StorageException("failed to verify minio readiness", exception);
        }
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
        try (InputStream inputStream = Files.newInputStream(tempFile)) {
            client().putObject(PutObjectArgs.builder()
                    .bucket(bucket())
                    .object(storageKey)
                    .stream(inputStream, Files.size(tempFile), -1)
                    .build());
        } catch (Exception exception) {
            throw new StorageException("failed to move file to permanent storage", exception);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            return client().getObject(GetObjectArgs.builder()
                    .bucket(bucket())
                    .object(storageKey)
                    .build());
        } catch (Exception exception) {
            throw new StorageException("failed to open stored file", exception);
        }
    }

    @Override
    public void deleteIfExists(String storageKey) {
        try {
            client().removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket())
                    .object(storageKey)
                    .build());
        } catch (ErrorResponseException exception) {
            if (isMissingObject(exception)) {
                return;
            }
            throw new StorageException("failed to delete stored file", exception);
        } catch (Exception exception) {
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
        try {
            client().statObject(StatObjectArgs.builder()
                    .bucket(bucket())
                    .object(storageKey)
                    .build());
            return true;
        } catch (ErrorResponseException exception) {
            if (isMissingObject(exception)) {
                return false;
            }
            throw new StorageException("failed to check stored file existence", exception);
        } catch (Exception exception) {
            throw new StorageException("failed to check stored file existence", exception);
        }
    }

    private boolean isEnabled() {
        return "minio".equalsIgnoreCase(config.storage().type());
    }

    private void cleanupTempDirectory() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(config.storage().tempDir())) {
            for (Path path : stream) {
                deleteRecursively(path);
            }
        }
    }

    private MinioClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = MinioClient.builder()
                            .endpoint(required(config.storage().minio().endpoint(), "mfs.storage.minio.endpoint"))
                            .credentials(
                                    required(config.storage().minio().accessKey(), "mfs.storage.minio.access-key"),
                                    required(config.storage().minio().secretKey(), "mfs.storage.minio.secret-key")
                            )
                            .build();
                }
            }
        }
        return client;
    }

    private boolean bucketExists() throws ErrorResponseException, MinioException, IOException, NoSuchAlgorithmException, InvalidKeyException {
        return client().bucketExists(BucketExistsArgs.builder().bucket(bucket()).build());
    }

    private String bucket() {
        return required(config.storage().minio().bucket(), "mfs.storage.minio.bucket");
    }

    private String required(java.util.Optional<String> value, String key) {
        return value.filter(item -> !item.isBlank())
                .orElseThrow(() -> new IllegalStateException("missing required minio config: " + key));
    }

    private boolean isMissingObject(ErrorResponseException exception) {
        String code = exception.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var walk = Files.walk(path)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(item -> {
                            try {
                                Files.deleteIfExists(item);
                            } catch (IOException exception) {
                                throw new RuntimeException(exception);
                            }
                        });
            } catch (RuntimeException exception) {
                if (exception.getCause() instanceof IOException ioException) {
                    throw ioException;
                }
                throw exception;
            }
            return;
        }
        Files.deleteIfExists(path);
    }
}
