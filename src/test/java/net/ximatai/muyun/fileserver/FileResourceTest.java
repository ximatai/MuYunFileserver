package net.ximatai.muyun.fileserver;

import de.huxhorn.sulky.ulid.ULID;
import io.quarkus.test.junit.QuarkusTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class FileResourceTest {

    private static final ULID ULID_GENERATOR = new ULID();
    private static final String TENANT_ID = "tenant-a";
    private static final String USER_ID = "u123";
    private static final Path STORAGE_ROOT = Path.of(System.getProperty("user.dir"), "build", "test-storage");
    private static final int MAX_FILE_SIZE_BYTES = 1024 * 1024;

    @Test
    void shouldUploadQueryDownloadAndDeleteFile() {
        String fileId = givenAuthenticated()
                .multiPart("files", "contract.txt", "hello file server".getBytes(), "text/plain")
                .multiPart("remark", "crm upload")
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(201)
                .body("success", equalTo(true))
                .body("data.items.size()", equalTo(1))
                .body("data.items[0].id", notNullValue())
                .extract()
                .path("data.items[0].id");

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.id", equalTo(fileId))
                .body("data.mimeType", equalTo("text/plain"))
                .body("data.remark", equalTo("crm upload"));

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}/download", fileId)
                .then()
                .statusCode(200)
                .header("Content-Type", Matchers.containsString("text/plain"))
                .header("Content-Disposition", Matchers.containsString("attachment;"))
                .body(equalTo("hello file server"));

        givenAuthenticated()
                .when()
                .delete("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.id", equalTo(fileId))
                .body("data.status", equalTo("DELETED"));

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(404);

        givenAuthenticated()
                .when()
                .delete("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(404);
    }

    @Test
    void shouldRejectUploadWhenOneFileIsUnsupportedAndRollbackWholeRequest() {
        givenAuthenticated()
                .multiPart("files", "ok.txt", "hello".getBytes(), "text/plain")
                .multiPart("files", "bad.sh", "echo hacked".getBytes(), "application/x-sh")
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(415)
                .body("success", equalTo(false));
    }

    @Test
    void shouldRejectMissingIdentityHeaders() {
        given()
                .multiPart("files", "contract.txt", "hello".getBytes(), "text/plain")
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(401)
                .body("success", equalTo(false));
    }

    @Test
    void shouldReturnForbiddenForTenantMismatch() {
        String fileId = givenAuthenticated()
                .multiPart("files", "contract.txt", "hello".getBytes(), "text/plain")
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(201)
                .extract()
                .path("data.items[0].id");

        given()
                .header("X-Tenant-Id", "tenant-b")
                .header("X-User-Id", "u123")
                .when()
                .get("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(403);
    }

    @Test
    void shouldRejectInvalidFileIdForMetadataQuery() {
        givenAuthenticated()
                .when()
                .get("/api/v1/files/not-a-ulid")
                .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("message", equalTo("invalid fileId"));
    }

    @Test
    void shouldRejectTooManyFileIds() {
        givenAuthenticated()
                .multiPart("files", "contract.txt", "hello".getBytes(), "text/plain")
                .multiPart("file_ids", "01ARZ3NDEKTSV4RRFFQ69G5FAV")
                .multiPart("file_ids", "01ARZ3NDEKTSV4RRFFQ69G5FAW")
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("message", equalTo("file_ids count cannot exceed files count"));
    }

    @Test
    void shouldRejectConflictingExplicitFileId() {
        String explicitFileId = ULID_GENERATOR.nextULID();

        givenAuthenticated()
                .multiPart("files", "first.txt", "hello".getBytes(), "text/plain")
                .multiPart("file_ids", explicitFileId)
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(201);

        givenAuthenticated()
                .multiPart("files", "second.txt", "world".getBytes(), "text/plain")
                .multiPart("file_ids", explicitFileId)
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(409)
                .body("success", equalTo(false))
                .body("message", equalTo("file_id already exists"));
    }

    @Test
    void shouldReturnInternalServerErrorWhenStoredFileIsMissing() throws Exception {
        String fileId = uploadSingleFile("missing.txt", "still tracked".getBytes(), "text/plain");
        Files.delete(storagePath(fileId));

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}/download", fileId)
                .then()
                .statusCode(500)
                .body("success", equalTo(false))
                .body("message", equalTo("stored file content is missing"));
    }

    @Test
    void shouldAllowEmptyFileUpload() {
        String fileId = uploadSingleFile("empty.txt", new byte[0], "text/plain");

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("data.sizeBytes", equalTo(0));

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}/download", fileId)
                .then()
                .statusCode(200)
                .header("Content-Length", "0")
                .body(equalTo(""));
    }

    @Test
    void shouldRejectOversizedFile() {
        byte[] oversized = new byte[MAX_FILE_SIZE_BYTES + 1];
        Arrays.fill(oversized, (byte) 'a');

        givenAuthenticated()
                .multiPart("files", "oversized.txt", oversized, "text/plain")
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(413)
                .body("success", equalTo(false))
                .body("message", equalTo("file exceeds max size limit"));
    }

    @Test
    void shouldAllowPartialExplicitFileIdsAndGenerateMissingOnes() {
        String explicitFileId = ULID_GENERATOR.nextULID();

        givenAuthenticated()
                .multiPart("files", "first.txt", "first".getBytes(), "text/plain")
                .multiPart("files", "second.txt", "second".getBytes(), "text/plain")
                .multiPart("file_ids", explicitFileId)
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(201)
                .body("data.items.size()", equalTo(2))
                .body("data.items[0].id", equalTo(explicitFileId))
                .body("data.items[1].id", Matchers.not(equalTo(explicitFileId)))
                .body("data.items[1].id", notNullValue());
    }

    @Test
    void shouldEncodeSpecialCharactersInContentDisposition() {
        String fileId = uploadSingleFile("report final(1).txt", "hello".getBytes(), "text/plain");

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}/download", fileId)
                .then()
                .statusCode(200)
                .header("Content-Disposition",
                        "attachment; filename=\"report final(1).txt\"; filename*=UTF-8''report%20final%281%29.txt");
    }

    @Test
    void readinessShouldBeUp() {
        given()
                .when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    private io.restassured.specification.RequestSpecification givenAuthenticated() {
        return given()
                .header("X-Tenant-Id", TENANT_ID)
                .header("X-User-Id", USER_ID);
    }

    private String uploadSingleFile(String filename, byte[] content, String contentType) {
        return givenAuthenticated()
                .multiPart("files", filename, content, contentType)
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(201)
                .extract()
                .path("data.items[0].id");
    }

    private Path storagePath(String fileId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return STORAGE_ROOT.resolve("tenant")
                .resolve(TENANT_ID)
                .resolve("%04d".formatted(today.getYear()))
                .resolve("%02d".formatted(today.getMonthValue()))
                .resolve("%02d".formatted(today.getDayOfMonth()))
                .resolve(fileId);
    }
}
