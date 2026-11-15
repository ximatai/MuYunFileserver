package net.ximatai.muyun.fileserver;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.application.FileReceptionService;
import net.ximatai.muyun.fileserver.domain.file.StoredObject;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileReceptionRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@QuarkusTestResource(value = ReceptionStorageRoutingTest.LegacyReception.class, restrictToAnnotatedClass = true)
class ReceptionStorageRoutingTest {
    private static final String OPEN = "legacy-open";
    private static final String EXPIRED = "legacy-expired";
    private static final String NEW_ASSETS = "new-assets-it";
    private static final String NEW_RECEPTION = "new-reception-it";
    private static final String CANDIDATE = "late-candidate-key";

    @Inject FileReceptionService service;
    @Inject FileReceptionRepository receptions;
    @Inject FileMetadataRepository files;

    @Test
    void existingReceptionsAndCandidatesKeepTheirOriginalLocations() throws Exception {
        assertEquals("old-reception", receptions.find(OPEN).orElseThrow().storageLocationId());
        assertEquals("new-reception", receptions.find(EXPIRED).orElseThrow().storageLocationId());
        assertEquals(new StoredObject("old-assets", CANDIDATE), receptions.staged(EXPIRED).get("legacy-candidate"));

        var manifest = Map.of("objects", List.of(Map.of("key", "part.txt", "filename", "part.txt", "sizeBytes", 3)));
        Map<String, String> assets = given().header("X-Tenant-Id", "tenant-a").header("X-User-Id", "tester")
                .header("Authorization", "Bearer reception-test-service-secret-only").contentType("application/json")
                .body(manifest).when().post("/api/v1/internal/receptions/" + OPEN + "/confirm")
                .then().statusCode(200).body("data.state", equalTo("READY")).extract().path("data.assets");
        String fileId = assets.get("part.txt");
        assertEquals("new-assets", files.findById(fileId).orElseThrow().storageLocationId());
        given().header("X-Tenant-Id", "tenant-a").header("X-User-Id", "tester")
                .when().get("/api/v1/files/" + fileId + "/download").then().statusCode(200).body(equalTo("old"));

        service.cleanup();
        // The same key in the new default location does not belong to the expired task.
        try (var body = LegacyReception.client().getObject(io.minio.GetObjectArgs.builder()
                .bucket(NEW_ASSETS).object(CANDIDATE).build())) {
            assertEquals("new", new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var missing = org.junit.jupiter.api.Assertions.assertThrows(io.minio.errors.ErrorResponseException.class,
                () -> LegacyReception.client().statObject(io.minio.StatObjectArgs.builder()
                        .bucket(SeaweedFileResourceTestResource.BUCKET).object(CANDIDATE).build()));
        assertEquals(404, missing.response().code());
    }

    public static class LegacyReception extends SeaweedFileResourceTestResource {
        @Override
        public Map<String, String> start() {
            var properties = new HashMap<>(super.start());
            try {
                var client = client();
                for (String bucket : List.of(BUCKET, RECEPTION_BUCKET, NEW_ASSETS, NEW_RECEPTION)) {
                    if (!client.bucketExists(io.minio.BucketExistsArgs.builder().bucket(bucket).build())) {
                        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    }
                }
                put(client, RECEPTION_BUCKET, OPEN + "/part.txt", "old");
                put(client, NEW_RECEPTION, OPEN + "/part.txt", "new");
                put(client, BUCKET, CANDIDATE, "old");
                put(client, NEW_ASSETS, CANDIDATE, "new");
                seed("jdbc:sqlite:" + properties.get("mfs.database.path"));
                location(properties, "old-assets", "asset", BUCKET);
                location(properties, "new-assets", "asset", NEW_ASSETS);
                location(properties, "old-reception", "reception", RECEPTION_BUCKET);
                location(properties, "new-reception", "reception", NEW_RECEPTION);
                properties.put("mfs.storage.default-location", "new-assets");
                properties.put("mfs.reception.location", "new-reception");
                properties.put("mfs.storage.legacy-asset-locations", "old-assets");
                properties.put("mfs.storage.legacy-reception-locations", "old-reception,new-reception");
                return properties;
            } catch (Exception e) {
                String diagnostics = server.getLogs();
                stop();
                throw new IllegalStateException("failed to seed a legacy reception\n" + diagnostics, e);
            }
        }

        private void seed(String url) throws Exception {
            Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target("5").load().migrate();
            try (var connection = DriverManager.getConnection(url);
                 var receptions = connection.prepareStatement("""
                         insert into file_reception(id,tenant_id,idempotency_key,prefix,state,max_objects,max_bytes,
                             expires_at,bucket,ttl_seconds)
                         values (?,'tenant-a',?,?,'OPEN',5,1024,?,?,3600)
                         """);
                 var candidates = connection.prepareStatement("""
                         insert into file_reception_staged(file_id,reception_id,lease_token,storage_key)
                         values ('legacy-candidate',?,'legacy-lease',?)
                         """)) {
                for (String id : List.of(OPEN, EXPIRED)) {
                    receptions.setString(1, id);
                    receptions.setString(2, id);
                    receptions.setString(3, id + "/");
                    receptions.setLong(4, id.equals(OPEN) ? System.currentTimeMillis() + 3_600_000 : 0);
                    receptions.setString(5, id.equals(OPEN) ? RECEPTION_BUCKET : NEW_RECEPTION);
                    receptions.executeUpdate();
                }
                candidates.setString(1, EXPIRED);
                candidates.setString(2, CANDIDATE);
                candidates.executeUpdate();
            }
        }

        private void location(Map<String, String> properties, String id, String role, String bucket) {
            String key = "mfs.storage.locations." + id + ".";
            properties.put(key + "type", "s3");
            properties.put(key + "role", role);
            properties.put(key + "s3.endpoint", endpoint());
            properties.put(key + "s3.bucket", bucket);
            properties.put(key + "s3.access-key", ACCESS_KEY);
            properties.put(key + "s3.secret-key", SECRET_KEY);
            properties.put(key + "s3.auto-create-bucket", "false");
        }

        private static MinioClient client() {
            return MinioClient.builder().endpoint(endpoint()).credentials(ACCESS_KEY, SECRET_KEY).build();
        }

        private void put(MinioClient client, String bucket, String key, String value) throws Exception {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
        }
    }
}
