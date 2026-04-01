package net.ximatai.muyun.fileserver;

import de.huxhorn.sulky.ulid.ULID;
import io.quarkus.test.junit.QuarkusTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class FileResourceTest {

    private static final ULID ULID_GENERATOR = new ULID();

    @Test
    void shouldUploadQueryDownloadAndDeleteFile() {
        String fileId = given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
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

        given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
                .when()
                .get("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.id", equalTo(fileId))
                .body("data.mimeType", equalTo("text/plain"))
                .body("data.remark", equalTo("crm upload"));

        given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
                .when()
                .get("/api/v1/files/{fileId}/download", fileId)
                .then()
                .statusCode(200)
                .header("Content-Type", Matchers.containsString("text/plain"))
                .header("Content-Disposition", Matchers.containsString("attachment;"))
                .body(equalTo("hello file server"));

        given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
                .when()
                .delete("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.id", equalTo(fileId))
                .body("data.status", equalTo("DELETED"));

        given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
                .when()
                .get("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(404);

        given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
                .when()
                .delete("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(404);
    }

    @Test
    void shouldRejectUploadWhenOneFileIsUnsupportedAndRollbackWholeRequest() {
        given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
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
        String fileId = given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
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
        given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
                .when()
                .get("/api/v1/files/not-a-ulid")
                .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("message", equalTo("invalid fileId"));
    }

    @Test
    void shouldRejectTooManyFileIds() {
        given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
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

        given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
                .multiPart("files", "first.txt", "hello".getBytes(), "text/plain")
                .multiPart("file_ids", explicitFileId)
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(201);

        given()
                .header("X-Tenant-Id", "tenant-a")
                .header("X-User-Id", "u123")
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
    void readinessShouldBeUp() {
        given()
                .when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }
}
