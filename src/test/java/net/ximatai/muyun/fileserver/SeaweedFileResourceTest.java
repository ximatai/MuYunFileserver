package net.ximatai.muyun.fileserver;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(value = SeaweedFileResourceTestResource.class, restrictToAnnotatedClass = true)
class SeaweedFileResourceTest {
    private static final String TENANT = "tenant-seaweed";

    @Test
    void shouldUploadReadRangeAndEnforceTenantBoundary() {
        String id = upload("sample.txt", "hello seaweed".getBytes(StandardCharsets.UTF_8));
        given().header("X-Tenant-Id", TENANT).header("X-User-Id", "tester")
                .header("Range", "bytes=6-12")
                .get("/api/v1/files/{id}/download", id).then().statusCode(206)
                .header("Content-Range", "bytes 6-12/13").body(equalTo("seaweed"));
        given().header("X-Tenant-Id", "another-tenant").header("X-User-Id", "tester")
                .get("/api/v1/files/{id}/download", id).then().statusCode(403);
        given().get("/q/health/ready").then().statusCode(200);
    }

    @Test
    void shouldRoundTripLargeFileThroughExistingProvider() throws Exception {
        byte[] content = new byte[12 * 1024 * 1024];
        new java.util.Random(42).nextBytes(content);
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(content).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(content.length - 8)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(content.length - 44);
        String id = upload("sample.wav", content);
        byte[] downloaded = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "tester")
                .get("/api/v1/files/{id}/download", id).then().statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(content),
                MessageDigest.getInstance("SHA-256").digest(downloaded));
    }

    @Test
    void shouldKeepSignedDownloadAndSoftDeleteSemantics() {
        String id = upload("signed.txt", "private audio metadata".getBytes(StandardCharsets.UTF_8));
        String path = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "tester")
                .contentType("application/json").body("{\"expiresInSeconds\":60}")
                .post("/api/v1/files/{id}/download-link", id).then().statusCode(200)
                .extract().path("data.downloadPath");
        given().get(path).then().statusCode(200).body(equalTo("private audio metadata"));
        given().header("X-Tenant-Id", TENANT).header("X-User-Id", "tester")
                .delete("/api/v1/files/{id}", id).then().statusCode(200)
                .body("data.status", equalTo("DELETED"));
        given().get(path).then().statusCode(404);
    }

    @Test
    void shouldSupportMultipartOverwriteListingAndRestartPersistence() throws Exception {
        MinioClient client = client(SeaweedFileResourceTestResource.SECRET_KEY);
        String key = "recording-test/" + UUID.randomUUID() + "/index.m3u8";
        byte[] content = new byte[12 * 1024 * 1024];
        new java.util.Random(7).nextBytes(content);
        // Explicit 5 MiB part size exercises a multipart upload rather than just a small PUT.
        client.putObject(PutObjectArgs.builder().bucket(bucket()).object(key)
                .stream(new ByteArrayInputStream(content), content.length, 5 * 1024 * 1024).build());
        assertEquals(content.length, client.statObject(StatObjectArgs.builder().bucket(bucket()).object(key).build()).size());
        try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket()).object(key).build())) {
            assertArrayEquals(content, input.readAllBytes());
        }
        byte[] replacement = "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder().bucket(bucket()).object(key)
                .stream(new ByteArrayInputStream(replacement), replacement.length, -1).build());
        assertTrue(client.listObjects(ListObjectsArgs.builder().bucket(bucket()).prefix(key).build())
                .iterator().hasNext());
        SeaweedFileResourceTestResource.server.getDockerClient()
                .restartContainerCmd(SeaweedFileResourceTestResource.server.getContainerId()).exec();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket()).object(key).build())) {
                assertArrayEquals(replacement, input.readAllBytes());
                lastFailure = null;
                break;
            } catch (Exception exception) {
                lastFailure = exception;
                Thread.sleep(200);
            }
        }
        assertNull(lastFailure, "stored object must survive S3 process restart");
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket()).object(key).build());
        assertThrows(ErrorResponseException.class, () ->
                client.statObject(StatObjectArgs.builder().bucket(bucket()).object(key).build()));
        given().get("/q/health/ready").then().statusCode(200);
    }

    @Test
    void shouldRoundTripConcurrentObjectsWithResourceSamples() throws Exception {
        MinioClient client = client(SeaweedFileResourceTestResource.SECRET_KEY);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(5)) {
            var samples = executor.submit(() -> {
                for (int i = 0; i < 4; i++) {
                    Process process = new ProcessBuilder("docker", "stats", "--no-stream", "--format",
                            "{{.CPUPerc}} {{.MemUsage}}", SeaweedFileResourceTestResource.server.getContainerId())
                            .redirectErrorStream(true).start();
                    String sample = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (process.waitFor() != 0) throw new IllegalStateException("resource sampling failed");
                    System.out.println("SEAWEED_RESOURCE_SAMPLE " + sample);
                }
                return true;
            });
            var uploads = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 4; i++) {
                final int seed = i;
                uploads.add(executor.submit(() -> {
                    byte[] content = new byte[24 * 1024 * 1024];
                    new java.util.Random(seed).nextBytes(content);
                    String key = "resource-probe/" + UUID.randomUUID() + "/media.bin";
                    try {
                        client.putObject(PutObjectArgs.builder().bucket(bucket()).object(key)
                                .stream(new ByteArrayInputStream(content), content.length, 5 * 1024 * 1024).build());
                        try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket()).object(key).build())) {
                            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(content),
                                    MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                        }
                    } finally {
                        client.removeObject(RemoveObjectArgs.builder().bucket(bucket()).object(key).build());
                    }
                    return true;
                }));
            }
            for (var upload : uploads) assertTrue(upload.get(60, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(samples.get(20, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldRejectWrongS3Signature() {
        ErrorResponseException failure = assertThrows(ErrorResponseException.class, () ->
                client("wrong-secret").bucketExists(BucketExistsArgs.builder().bucket(bucket()).build()));
        assertEquals(403, failure.response().code());
    }

    private String upload(String filename, byte[] content) {
        return given().header("X-Tenant-Id", TENANT).header("X-User-Id", "tester")
                .multiPart("files", filename, content, "application/octet-stream")
                .post("/api/v1/files").then().statusCode(200)
                .body("data.items[0].id", notNullValue()).extract().path("data.items[0].id");
    }

    private MinioClient client(String secret) {
        MinioClient client = MinioClient.builder().endpoint(SeaweedFileResourceTestResource.endpoint())
                .credentials(SeaweedFileResourceTestResource.ACCESS_KEY, secret).build();
        client.setTimeout(3000, 10000, 10000);
        return client;
    }

    private String bucket() {
        return SeaweedFileResourceTestResource.BUCKET;
    }
}
