package net.ximatai.muyun.fileserver;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.admin.MinioAdminClient;
import io.minio.admin.UserInfo;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.MinIOContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

public class MinioReceptionTestResource implements QuarkusTestResourceLifecycleManager {
    private static final String ASSETS = "muyun-files";
    private static final String RECEPTION = "muyun-recording";
    private MinIOContainer server;
    private Path data;

    @Override
    public Map<String, String> start() {
        try {
            data = Files.createTempDirectory("mfs-minio-reception-");
            server = new MinIOContainer(MinioTestImage.name())
                    .withUserName("minioadmin").withPassword("minioadmin");
            server.start();

            String endpoint = server.getS3URL();
            String accessKey = server.getUserName();
            String secretKey = server.getPassword();
            var s3 = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
            s3.makeBucket(MakeBucketArgs.builder().bucket(ASSETS).build());
            s3.makeBucket(MakeBucketArgs.builder().bucket(RECEPTION).build());
            var admin = MinioAdminClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
            String policy = Files.readString(Path.of("distribution/release/minio-recorder-policy.example.json"));
            admin.addCannedPolicy("recorder", policy);
            admin.addUser(S3ReceptionContract.RECORDER_ACCESS_KEY, UserInfo.Status.ENABLED,
                    S3ReceptionContract.RECORDER_SECRET_KEY, null, null);
            admin.setPolicy(S3ReceptionContract.RECORDER_ACCESS_KEY, false, "recorder");

            return Map.ofEntries(
                    Map.entry("quarkus.http.test-port", "0"),
                    Map.entry("mfs.database.path", data.resolve("assets.db").toString()),
                    Map.entry("mfs.storage.temp-dir", data.resolve("workspace").toString()),
                    Map.entry("mfs.storage.type", "s3"),
                    Map.entry("mfs.storage.s3.endpoint", endpoint),
                    Map.entry("mfs.storage.s3.access-key", accessKey),
                    Map.entry("mfs.storage.s3.secret-key", secretKey),
                    Map.entry("mfs.storage.s3.bucket", ASSETS),
                    Map.entry("mfs.storage.s3.auto-create-bucket", "false"),
                    Map.entry("mfs.reception.enabled", "true"),
                    Map.entry("mfs.reception.bucket", RECEPTION),
                    Map.entry("mfs.reception.service-token", "reception-test-service-secret-only"),
                    Map.entry("mfs.upload.max-file-size-bytes", "33554432"),
                    Map.entry("mfs.upload.min-free-space-bytes", "1"),
                    Map.entry("quarkus.http.limits.max-body-size", "40M"));
        } catch (Exception e) {
            stop();
            throw new IllegalStateException("failed to configure isolated MinIO reception", e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (data != null) {
            Path directory = data;
            data = null;
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (Exception e) {
                throw new IllegalStateException("failed to remove MinIO reception fixture", e);
            }
        }
    }
}
