package net.ximatai.muyun.fileserver;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.MinIOContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class MinioFileResourceTestResource implements QuarkusTestResourceLifecycleManager {

    private MinIOContainer minio;
    private Path rootDir;
    private Path tempDir;
    private Path databasePath;

    @Override
    public Map<String, String> start() {
        try {
            rootDir = Path.of(System.getProperty("user.dir"), "build", "minio-it-storage");
            tempDir = Path.of(System.getProperty("user.dir"), "build", "minio-it-tmp");
            databasePath = Path.of(System.getProperty("user.dir"), "build", "minio-it.db");
            Files.createDirectories(rootDir);
            Files.createDirectories(tempDir);
            Files.deleteIfExists(databasePath);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to prepare minio test resource paths", exception);
        }

        minio = new MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z")
                .withUserName("minioadmin")
                .withPassword("minioadmin");
        minio.start();

        return Map.of(
                "mfs.storage.type", "minio",
                "mfs.storage.root-dir", rootDir.toString(),
                "mfs.storage.temp-dir", tempDir.toString(),
                "mfs.storage.minio.endpoint", minio.getS3URL(),
                "mfs.storage.minio.access-key", minio.getUserName(),
                "mfs.storage.minio.secret-key", minio.getPassword(),
                "mfs.storage.minio.bucket", "muyun-files-it",
                "mfs.storage.minio.auto-create-bucket", "true",
                "mfs.database.path", databasePath.toString(),
                "mfs.upload.min-free-space-bytes", "1"
        );
    }

    @Override
    public void stop() {
        if (minio != null) {
            minio.stop();
        }
        try {
            Files.deleteIfExists(databasePath);
        } catch (Exception ignored) {
        }
    }
}
