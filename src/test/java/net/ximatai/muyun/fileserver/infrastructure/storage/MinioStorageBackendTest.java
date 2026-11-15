package net.ximatai.muyun.fileserver.infrastructure.storage;

import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MinIOContainer;
import net.ximatai.muyun.fileserver.MinioTestImage;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinioStorageBackendTest {

    private static final String BUCKET = "muyun-files";
    private static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.name())
            .withUserName("minioadmin")
            .withPassword("minioadmin");

    @TempDir Path workspace;

    @BeforeAll
    static void startContainer() {
        MINIO.start();
    }

    @AfterAll
    static void stopContainer() {
        MINIO.stop();
    }

    @Test
    void shouldStoreReadAndDeleteObject() throws Exception {
        S3StorageBackend provider = new S3StorageBackend(
                MINIO.getS3URL(),
                MINIO.getUserName(),
                MINIO.getPassword(),
                BUCKET, true
        );
        provider.initialize();

        Path tempFile = Files.createTempFile(workspace, "object", ".tmp");
        Files.writeString(tempFile, "hello minio", StandardCharsets.UTF_8);

        provider.write(tempFile, "tenant-a/2026/04/02/file-1");

        assertTrue(provider.exists("tenant-a/2026/04/02/file-1"));

        try (InputStream inputStream = provider.open("tenant-a/2026/04/02/file-1")) {
            assertEquals("hello minio", new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }

        provider.deleteIfExists("tenant-a/2026/04/02/file-1");
        assertFalse(provider.exists("tenant-a/2026/04/02/file-1"));
    }

    @Test
    void shouldCreateBucketOnStartupWhenMissing() throws Exception {
        S3StorageBackend provider = new S3StorageBackend(
                MINIO.getS3URL(),
                MINIO.getUserName(),
                MINIO.getPassword(),
                BUCKET + "-startup", true
        );

        provider.initialize();
        provider.verifyReadiness();

        MinioClient client = MinioClient.builder()
                .endpoint(MINIO.getS3URL())
                .credentials(MINIO.getUserName(), MINIO.getPassword())
                .build();

        assertTrue(client.bucketExists(BucketExistsArgs.builder()
                .bucket(BUCKET + "-startup")
                .build()));
    }
}
