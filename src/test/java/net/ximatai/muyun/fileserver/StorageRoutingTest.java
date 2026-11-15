package net.ximatai.muyun.fileserver;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.job.FileCleanupJob;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(value = StorageRoutingTest.LegacyDatabase.class, restrictToAnnotatedClass = true)
class StorageRoutingTest {
    private static final String OLD_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    @Inject FileServiceConfig config;
    @Inject FileMetadataRepository files;
    @Inject FileCleanupJob cleanup;

    @Test
    void upgradingWithNewDefaultKeepsLegacyDownloadsPreviewsAndCleanupOnTheirOriginalLocation() throws Exception {
        var storage = config.storage().locations();
        Path oldRoot = storage.get("old").rootDir().orElseThrow();
        Path newRoot = storage.get("new").rootDir().orElseThrow();
        String api = "/api/v1/files/" + OLD_ID;
        var legacy = files.findById(OLD_ID).orElseThrow();
        assertEquals("old", legacy.storageLocationId());

        authenticated().when().get(api + "/download").then().statusCode(200).body(equalTo("legacy-bytes"));
        authenticated().header("Range", "bytes=0-5").when().get(api + "/view/content")
                .then().statusCode(206).body(equalTo("legacy"));

        String id = authenticated().multiPart("files", "fresh.txt", "fresh".getBytes(), "text/plain")
                .when().post("/api/v1/files").then().statusCode(200).extract().path("data.items[0].id");
        var fresh = files.findById(id).orElseThrow();
        assertEquals("new", fresh.storageLocationId());
        assertEquals("fresh", Files.readString(newRoot.resolve(fresh.storageKey())));
        assertFalse(Files.exists(oldRoot.resolve(fresh.storageKey())));

        files.softDelete(OLD_ID, "tenant-a", "tester", java.time.Instant.EPOCH);
        cleanup.sweepNow();
        assertTrue(files.findById(OLD_ID).isEmpty());
        assertFalse(Files.exists(oldRoot.resolve("old-object")));
        assertFalse(Files.exists(oldRoot.resolve("old-preview")));
        assertEquals("other-object", Files.readString(newRoot.resolve("old-object")));
        assertEquals("other-preview", Files.readString(newRoot.resolve("old-preview")));
    }

    private io.restassured.specification.RequestSpecification authenticated() {
        return given().header("X-Tenant-Id", "tenant-a").header("X-User-Id", "tester");
    }

    public static class LegacyDatabase implements QuarkusTestResourceLifecycleManager {
        private Path directory;

        @Override
        public Map<String, String> start() {
            try {
                directory = Files.createTempDirectory("mfs-storage-routing-");
                Path oldRoot = Files.createDirectory(directory.resolve("old"));
                Path newRoot = Files.createDirectory(directory.resolve("new"));
                Files.writeString(oldRoot.resolve("old-object"), "legacy-bytes");
                Files.writeString(oldRoot.resolve("old-preview"), "legacy-preview");
                Files.writeString(newRoot.resolve("old-object"), "other-object");
                Files.writeString(newRoot.resolve("old-preview"), "other-preview");
                Path db = directory.resolve("files.db");
                String url = "jdbc:sqlite:" + db;
                Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target("5").load().migrate();
                try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
                    sql.executeUpdate("""
                            insert into file_metadata(id,tenant_id,original_filename,extension,mime_type,size_bytes,sha256,
                                storage_provider,storage_key,status,uploaded_by,uploaded_at)
                            values ('01ARZ3NDEKTSV4RRFFQ69G5FAV','tenant-a','old.docx','docx',
                                'application/vnd.openxmlformats-officedocument.wordprocessingml.document',12,'legacy','local',
                                'old-object','ACTIVE','tester','2025-01-01T00:00:00Z')
                            """);
                    sql.executeUpdate("""
                            insert into file_preview_artifact(file_id,artifact_key,tenant_id,source_kind,status,target_mime_type,
                                storage_provider,storage_key,size_bytes,sha256,generated_at,last_accessed_at)
                            values ('01ARZ3NDEKTSV4RRFFQ69G5FAV','view_pdf','tenant-a','GENERATED_PDF','READY','application/pdf',
                                'local','old-preview',14,'legacy','2025-01-01T00:00:00Z','2025-01-01T00:00:00Z')
                            """);
                }
                // An unused binding left by a failed startup must not prevent correcting its configuration.
                Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").load().migrate();
                try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
                    sql.executeUpdate("""
                            insert into storage_location_binding values ('new','local:/wrong-initial-path','ASSET')
                            """);
                }
                return Map.of(
                        "mfs.database.path", db.toString(),
                        "mfs.storage.temp-dir", directory.resolve("workspace").toString(),
                        "mfs.storage.default-location", "new",
                        "mfs.storage.legacy-asset-locations", "old",
                        "mfs.storage.locations.old.type", "local",
                        "mfs.storage.locations.old.root-dir", oldRoot.toString(),
                        "mfs.storage.locations.new.type", "local",
                        "mfs.storage.locations.new.root-dir", newRoot.toString());
            } catch (Exception e) {
                throw new IllegalStateException("failed to prepare legacy storage fixture", e);
            }
        }

        @Override
        public void stop() {
            if (directory == null) {
                return;
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (Exception e) {
                throw new IllegalStateException("failed to remove storage fixture", e);
            }
        }
    }
}
