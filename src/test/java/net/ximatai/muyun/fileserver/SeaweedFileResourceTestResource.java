package net.ximatai.muyun.fileserver;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Isolated real S3 server; never uses the application's storage or database. */
public class SeaweedFileResourceTestResource implements QuarkusTestResourceLifecycleManager {
    static final String BUCKET = "muyun-seaweed-it";
    static final String RECEPTION_BUCKET = "muyun-recording-it";
    static final String ACCESS_KEY = "seaweed-test-user";
    static final String SECRET_KEY = "seaweed-test-secret-only";
    static GenericContainer<?> server;
    private Path testDirectory;

    @Override
    public Map<String, String> start() {
        try {
            testDirectory = Files.createTempDirectory("muyun-seaweed-it-");
            Files.createDirectories(testDirectory.resolve("storage"));
            Files.createDirectories(testDirectory.resolve("tmp"));
            Path identities=testDirectory.resolve("s3.json");
            Files.writeString(identities,"""
                    {"identities":[
                    {"name":"fileserver","credentials":[{"accessKey":"seaweed-test-user","secretKey":"seaweed-test-secret-only"}],"actions":["Admin","Read","Write","List"]},
                    {"name":"recorder","credentials":[{"accessKey":"recorder-test-user","secretKey":"recorder-test-secret-only"}],"actions":["Read:muyun-recording-it","Write:muyun-recording-it","List:muyun-recording-it"]}
                    ]}
                    """);
            int port;
            try (var socket = new java.net.ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            server = new GenericContainer<>(DockerImageName.parse(
                    "chrislusf/seaweedfs:4.48@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d"))
                    .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
                    .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                    .withEnv("S3_BUCKET", BUCKET+","+RECEPTION_BUCKET)
                    .withCopyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(identities),"/etc/seaweedfs/s3.json")
                    .withCommand("mini", "-dir=/data", "-admin.ui=false", "-webdav=false",
                            "-s3.port.iceberg=0", "-s3.port.lance=0", "-s3.config=/etc/seaweedfs/s3.json")
                    .withExposedPorts(8333)
                    // Docker can reassign an automatically published port on restart.
                    // Keep the endpoint stable so this test exercises storage recovery.
                    .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", port), ExposedPort.tcp(8333))))
                    .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403)
                            .withStartupTimeout(Duration.ofSeconds(60)));
            server.start();
            return Map.ofEntries(
                    Map.entry("mfs.storage.type", "minio"),
                    Map.entry("mfs.storage.root-dir", testDirectory.resolve("storage").toString()),
                    Map.entry("mfs.storage.temp-dir", testDirectory.resolve("tmp").toString()),
                    Map.entry("mfs.storage.minio.endpoint", endpoint()),
                    Map.entry("mfs.storage.minio.access-key", ACCESS_KEY),
                    Map.entry("mfs.storage.minio.secret-key", SECRET_KEY),
                    Map.entry("mfs.storage.minio.bucket", BUCKET),
                    Map.entry("mfs.storage.minio.auto-create-bucket", "false"),
                    Map.entry("mfs.database.path", testDirectory.resolve("files.db").toString()),
                    Map.entry("mfs.upload.max-file-size-bytes", "33554432"),
                    Map.entry("mfs.upload.min-free-space-bytes", "1"),
                    Map.entry("quarkus.http.limits.max-body-size", "40M"),
                    Map.entry("mfs.token.issuer", "seaweed-it")
                    ,Map.entry("mfs.reception.enabled", "true")
                    ,Map.entry("mfs.reception.bucket", RECEPTION_BUCKET)
                    ,Map.entry("mfs.reception.service-token", "reception-test-service-secret-only")
                    ,Map.entry("mfs.reception.allowed-tenants", "tenant-seaweed,tenant-reception-b")
            );
        } catch (Exception exception) {
            stop();
            throw new IllegalStateException("failed to start isolated SeaweedFS test", exception);
        }
    }

    static String endpoint() {
        return "http://" + server.getHost() + ":" + server.getMappedPort(8333);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (testDirectory != null) {
            try (var paths = Files.walk(testDirectory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (Exception exception) {
                throw new IllegalStateException("failed to clean isolated test files", exception);
            }
        }
    }
}
