package net.ximatai.muyun.fileserver;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@QuarkusTestResource(value = MinioFileResourceTestResource.class, restrictToAnnotatedClass = true)
class MinioFileResourceTest {

    @Test
    void shouldUploadDownloadAndDeleteFileUsingMinioStorage() {
        String fileId = given()
                .header("X-Tenant-Id", "tenant-minio")
                .header("X-User-Id", "u123")
                .multiPart("files", "contract.txt", "hello minio storage".getBytes(), "text/plain")
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(201)
                .body("success", equalTo(true))
                .body("data.items[0].id", notNullValue())
                .extract()
                .path("data.items[0].id");

        given()
                .header("X-Tenant-Id", "tenant-minio")
                .header("X-User-Id", "u123")
                .when()
                .get("/api/v1/files/{fileId}/download", fileId)
                .then()
                .statusCode(200)
                .header("Content-Type", Matchers.containsString("text/plain"))
                .body(equalTo("hello minio storage"));

        given()
                .header("X-Tenant-Id", "tenant-minio")
                .header("X-User-Id", "u123")
                .when()
                .delete("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(fileId))
                .body("data.status", equalTo("DELETED"));
    }
}
