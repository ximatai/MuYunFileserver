package net.ximatai.muyun.fileserver;

import de.huxhorn.sulky.ulid.ULID;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.job.FileCleanupJob;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class FileResourceTest {

    private static final ULID ULID_GENERATOR = new ULID();
    private static final String TENANT_ID = "tenant-a";
    private static final String USER_ID = "u123";
    private static final Path STORAGE_ROOT = Path.of(System.getProperty("user.dir"), "build", "test-storage");
    private static final int MAX_FILE_SIZE_BYTES = 1024 * 1024;
    private static final String TOKEN_SECRET = "test-token-secret";

    @Inject
    DataSource dataSource;

    @Inject
    FileCleanupJob fileCleanupJob;

    @Test
    void shouldUploadQueryDownloadAndDeleteFile() {
        String fileId = givenAuthenticated()
                .multiPart("files", "contract.txt", "hello file server".getBytes(), "text/plain")
                .multiPart("remark", "crm upload")
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(200)
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
                .body("data.temporary", equalTo(false))
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
    void shouldRenameFileAndUseRenamedFilenameForDownload() {
        String fileId = uploadSingleFile("contract.txt", "hello rename".getBytes(), "text/plain");

        givenAuthenticated()
                .contentType("application/json")
                .body("""
                        {
                          "originalFilename": "renamed-contract.txt"
                        }
                        """)
                .when()
                .put("/api/v1/files/{fileId}/name", fileId)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.id", equalTo(fileId))
                .body("data.originalFilename", equalTo("renamed-contract.txt"))
                .body("data.extension", equalTo("txt"));

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}/download", fileId)
                .then()
                .statusCode(200)
                .header("Content-Disposition", Matchers.containsString("renamed-contract.txt"))
                .body(equalTo("hello rename"));
    }

    @Test
    void shouldPromoteTemporaryFilesInBatch() {
        String firstFileId = uploadSingleFile("draft-a.txt", "a".getBytes(), "text/plain", true);
        String secondFileId = uploadSingleFile("draft-b.txt", "b".getBytes(), "text/plain", true);

        givenAuthenticated()
                .contentType("application/json")
                .body("""
                        {
                          "fileIds": ["%s", "%s"]
                        }
                        """.formatted(firstFileId, secondFileId))
                .when()
                .post("/api/v1/files/promote")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.items.size()", equalTo(2))
                .body("data.items[0].id", equalTo(firstFileId))
                .body("data.items[0].temporary", equalTo(false))
                .body("data.items[1].id", equalTo(secondFileId))
                .body("data.items[1].temporary", equalTo(false));

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}", firstFileId)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(firstFileId))
                .body("data.temporary", equalTo(false));
    }

    @Test
    void shouldCleanupTemporaryFileAfterRetention() throws Exception {
        String fileId = uploadSingleFile("temporary-cleanup.txt", "cleanup".getBytes(), "text/plain", true);

        ageTemporaryFile(fileId, Instant.now().minusSeconds(60L * 60L * 25L));
        runCleanupJob();

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(404)
                .body("success", equalTo(false))
                .body("message", equalTo("file not found"));
    }

    @Test
    void shouldNotCleanupPromotedFileInTemporarySweep() throws Exception {
        String fileId = uploadSingleFile("promoted-keep.txt", "keep me".getBytes(), "text/plain", true);

        givenAuthenticated()
                .contentType("application/json")
                .body("""
                        {
                          "fileIds": ["%s"]
                        }
                        """.formatted(fileId))
                .when()
                .post("/api/v1/files/promote")
                .then()
                .statusCode(200)
                .body("success", equalTo(true));

        ageTemporaryFile(fileId, Instant.now().minusSeconds(60L * 60L * 25L));
        runCleanupJob();

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(fileId));
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
                .statusCode(200)
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
                .statusCode(200);

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
    void shouldReturnNotFoundWhenStoredFileIsMissingForTrustedDownload() throws Exception {
        String fileId = uploadSingleFile("missing.txt", "still tracked".getBytes(), "text/plain");
        Files.delete(storagePath(fileId));

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}/download", fileId)
                .then()
                .statusCode(404)
                .body("success", equalTo(false))
                .body("message", equalTo("file not found"));
    }

    @Test
    void shouldReturnNotFoundWhenStoredFileIsMissingForTokenDownload() throws Exception {
        String fileId = uploadSingleFile("missing-public.txt", "still tracked".getBytes(), "text/plain");
        String accessToken = signReadToken(TENANT_ID, fileId, Instant.now().plusSeconds(60));
        Files.delete(storagePath(fileId));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .get("/api/v1/public/files/{fileId}/download", fileId)
                .then()
                .statusCode(404)
                .body("success", equalTo(false))
                .body("message", equalTo("file not found"));
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
    void shouldPreviewPdfFileAndReturnInlineContent() {
        byte[] pdfBytes = """
                %PDF-1.4
                1 0 obj
                << /Type /Catalog /Pages 2 0 R >>
                endobj
                xref
                0 1
                0000000000 65535 f 
                trailer
                << /Size 1 /Root 1 0 R >>
                startxref
                9
                %%EOF
                """.getBytes(StandardCharsets.UTF_8);
        String fileId = uploadSingleFile("contract.pdf", pdfBytes, "application/pdf");

        givenAuthenticated()
                .redirects().follow(false)
                .when()
                .get("/api/v1/files/{fileId}/preview", fileId)
                .then()
                .statusCode(302)
                .header("Location", equalTo("preview/content"));

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}/preview/content", fileId)
                .then()
                .statusCode(200)
                .header("Content-Type", Matchers.containsString("application/pdf"))
                .header("Content-Disposition", Matchers.containsString("inline;"))
                .body(equalTo(new String(pdfBytes, StandardCharsets.UTF_8)));
    }

    @Test
    void shouldGeneratePreviewForOfficeFileAndReuseCachedPdf() {
        String fileId = uploadSingleFile(
                "report.docx",
                minimalDocxBytes(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );

        givenAuthenticated()
                .redirects().follow(false)
                .when()
                .get("/api/v1/files/{fileId}/preview", fileId)
                .then()
                .statusCode(302)
                .header("Location", equalTo("preview/content"));

        Path previewPath = previewStoragePath(fileId);
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(previewPath));

        long modifiedTime = previewPath.toFile().lastModified();

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}/preview/content", fileId)
                .then()
                .statusCode(200)
                .header("Content-Type", Matchers.containsString("application/pdf"))
                .header("Content-Disposition", Matchers.containsString("inline;"))
                .body(Matchers.containsString("Fake Preview PDF"));

        givenAuthenticated()
                .redirects().follow(false)
                .when()
                .get("/api/v1/files/{fileId}/preview", fileId)
                .then()
                .statusCode(302);

        org.junit.jupiter.api.Assertions.assertEquals(modifiedTime, previewPath.toFile().lastModified());
    }

    @Test
    void shouldRejectPreviewForUnsupportedFileType() {
        String fileId = uploadSingleFile("image.png", "png".getBytes(StandardCharsets.UTF_8), "image/png");

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}/preview", fileId)
                .then()
                .statusCode(415)
                .body("success", equalTo(false))
                .body("message", equalTo("preview is not supported for current file type"));
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
                .statusCode(200)
                .body("data.items.size()", equalTo(2))
                .body("data.items[0].id", equalTo(explicitFileId))
                .body("data.items[1].id", Matchers.not(equalTo(explicitFileId)))
                .body("data.items[1].id", notNullValue());
    }

    @Test
    void shouldUploadFileWithValidUploadToken() throws Exception {
        String accessToken = signUploadToken(TENANT_ID, USER_ID, Instant.now().plusSeconds(60));

        String fileId = given()
                .queryParam("access_token", accessToken)
                .multiPart("files", "public-upload.txt", "token upload".getBytes(), "text/plain")
                .multiPart("remark", "public upload")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(200)
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
                .body("data.id", equalTo(fileId))
                .body("data.remark", equalTo("public upload"))
                .body("data.uploadedBy", equalTo(USER_ID));
    }

    @Test
    void shouldUploadMultipleFilesWithUploadToken() throws Exception {
        String accessToken = signUploadToken(TENANT_ID, USER_ID, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .multiPart("files", "first.txt", "first".getBytes(), "text/plain")
                .multiPart("files", "second.txt", "second".getBytes(), "text/plain")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.items.size()", equalTo(2));
    }

    @Test
    void shouldRejectMissingUploadAccessToken() {
        given()
                .multiPart("files", "public-upload.txt", "token upload".getBytes(), "text/plain")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(401)
                .body("success", equalTo(false))
                .body("message", equalTo("missing access_token"));
    }

    @Test
    void shouldRejectExpiredUploadToken() throws Exception {
        String accessToken = signUploadToken(TENANT_ID, USER_ID, Instant.now().minusSeconds(5));

        given()
                .queryParam("access_token", accessToken)
                .multiPart("files", "expired-upload.txt", "token upload".getBytes(), "text/plain")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(401)
                .body("success", equalTo(false))
                .body("message", equalTo("upload token expired"));
    }

    @Test
    void shouldRejectInvalidUploadToken() {
        given()
                .queryParam("access_token", "invalid-token")
                .multiPart("files", "public-upload.txt", "token upload".getBytes(), "text/plain")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(401)
                .body("success", equalTo(false))
                .body("message", equalTo("invalid upload token"));
    }

    @Test
    void shouldRejectUploadTokenWithInvalidPurpose() throws Exception {
        String accessToken = signUploadToken("""
                {"iss":"biz-app","sub":"%s","purpose":"delete","tenant_id":"%s","exp":%d}
                """.formatted(USER_ID, TENANT_ID, Instant.now().plusSeconds(60).getEpochSecond()).trim());

        given()
                .queryParam("access_token", accessToken)
                .multiPart("files", "public-upload.txt", "token upload".getBytes(), "text/plain")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(403)
                .body("success", equalTo(false))
                .body("message", equalTo("upload token purpose is not valid for upload"));
    }

    @Test
    void shouldRejectFileIdsForTokenUpload() throws Exception {
        String accessToken = signUploadToken(TENANT_ID, USER_ID, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .multiPart("files", "public-upload.txt", "token upload".getBytes(), "text/plain")
                .multiPart("file_ids", ULID_GENERATOR.nextULID())
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("message", equalTo("file_ids is not supported for token upload"));
    }

    @Test
    void shouldRejectMultipartWithoutFilesForTokenUpload() throws Exception {
        String accessToken = signUploadToken(TENANT_ID, USER_ID, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .multiPart("remark", "public upload")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("message", equalTo("files is required"));
    }

    @Test
    void shouldRejectTooManyFilesForTokenUpload() throws Exception {
        String accessToken = signUploadToken(TENANT_ID, USER_ID, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .multiPart("files", "1.txt", "1".getBytes(), "text/plain")
                .multiPart("files", "2.txt", "2".getBytes(), "text/plain")
                .multiPart("files", "3.txt", "3".getBytes(), "text/plain")
                .multiPart("files", "4.txt", "4".getBytes(), "text/plain")
                .multiPart("files", "5.txt", "5".getBytes(), "text/plain")
                .multiPart("files", "6.txt", "6".getBytes(), "text/plain")
                .multiPart("files", "7.txt", "7".getBytes(), "text/plain")
                .multiPart("files", "8.txt", "8".getBytes(), "text/plain")
                .multiPart("files", "9.txt", "9".getBytes(), "text/plain")
                .multiPart("files", "10.txt", "10".getBytes(), "text/plain")
                .multiPart("files", "11.txt", "11".getBytes(), "text/plain")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("message", equalTo("too many files in one request"));
    }

    @Test
    void shouldRejectOversizedFileForTokenUpload() throws Exception {
        String accessToken = signUploadToken(TENANT_ID, USER_ID, Instant.now().plusSeconds(60));
        byte[] oversized = new byte[MAX_FILE_SIZE_BYTES + 1];
        Arrays.fill(oversized, (byte) 'a');

        given()
                .queryParam("access_token", accessToken)
                .multiPart("files", "oversized.txt", oversized, "text/plain")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(413)
                .body("success", equalTo(false))
                .body("message", equalTo("file exceeds max size limit"));
    }

    @Test
    void shouldRejectUnsupportedMimeTypeForTokenUpload() throws Exception {
        String accessToken = signUploadToken(TENANT_ID, USER_ID, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .multiPart("files", "bad.sh", "echo hacked".getBytes(), "application/x-sh")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(415)
                .body("success", equalTo(false))
                .body("message", equalTo("unsupported media type"));
    }

    @Test
    void shouldRejectReadTokenForUpload() throws Exception {
        String readToken = signToken("""
                {"iss":"biz-app","sub":"%s","tenant_id":"%s","file_id":"%s","exp":%d}
                """.formatted(USER_ID, TENANT_ID, ULID_GENERATOR.nextULID(), Instant.now().plusSeconds(60).getEpochSecond()).trim(), TOKEN_SECRET);

        given()
                .queryParam("access_token", readToken)
                .multiPart("files", "public-upload.txt", "token upload".getBytes(), "text/plain")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(403)
                .body("success", equalTo(false))
                .body("message", equalTo("upload token purpose is not valid for upload"));
    }

    @Test
    void shouldQueryAndDownloadFileUploadedByToken() throws Exception {
        String uploadToken = signUploadToken(TENANT_ID, USER_ID, Instant.now().plusSeconds(60));

        String fileId = given()
                .queryParam("access_token", uploadToken)
                .multiPart("files", "public-roundtrip.txt", "roundtrip".getBytes(), "text/plain")
                .when()
                .post("/api/v1/public/files")
                .then()
                .statusCode(200)
                .extract()
                .path("data.items[0].id");

        String readToken = signReadToken(TENANT_ID, fileId, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", readToken)
                .when()
                .get("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(fileId));

        given()
                .queryParam("access_token", readToken)
                .when()
                .get("/api/v1/public/files/{fileId}/download", fileId)
                .then()
                .statusCode(200)
                .body(equalTo("roundtrip"));
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
    void shouldDownloadFileWithValidAccessToken() throws Exception {
        String fileId = uploadSingleFile("public.txt", "token download".getBytes(), "text/plain");
        String accessToken = signReadToken(TENANT_ID, fileId, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .get("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.id", equalTo(fileId))
                .body("data.mimeType", equalTo("text/plain"));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .get("/api/v1/public/files/{fileId}/download", fileId)
                .then()
                .statusCode(200)
                .header("Content-Type", Matchers.containsString("text/plain"))
                .body(equalTo("token download"));
    }

    @Test
    void shouldRejectExpiredAccessToken() throws Exception {
        String fileId = uploadSingleFile("expired.txt", "expired".getBytes(), "text/plain");
        String accessToken = signReadToken(TENANT_ID, fileId, Instant.now().minusSeconds(5));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .get("/api/v1/public/files/{fileId}/download", fileId)
                .then()
                .statusCode(401)
                .body("success", equalTo(false))
                .body("message", equalTo("download token expired"));
    }

    @Test
    void shouldRejectAccessTokenForDifferentFile() throws Exception {
        String fileId = uploadSingleFile("mismatch.txt", "mismatch".getBytes(), "text/plain");
        String otherFileId = ULID_GENERATOR.nextULID();
        String accessToken = signReadToken(TENANT_ID, otherFileId, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .get("/api/v1/public/files/{fileId}/download", fileId)
                .then()
                .statusCode(403)
                .body("success", equalTo(false))
                .body("message", equalTo("download token does not match requested file"));
    }

    @Test
    void shouldRejectInvalidFileIdForPublicMetadataQuery() throws Exception {
        String accessToken = signReadToken(TENANT_ID, ULID_GENERATOR.nextULID(), Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .get("/api/v1/public/files/not-a-ulid")
                .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("message", equalTo("invalid fileId"));
    }

    @Test
    void shouldRejectReadTokenForTenantMismatch() throws Exception {
        String fileId = uploadSingleFile("tenant-mismatch-read.txt", "read".getBytes(), "text/plain");
        String accessToken = signReadToken("tenant-b", fileId, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .get("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(403)
                .body("success", equalTo(false))
                .body("message", equalTo("download token is not valid for current tenant"));
    }

    @Test
    void shouldReturnNotFoundForPublicMetadataQueryAfterDelete() throws Exception {
        String fileId = uploadSingleFile("deleted-query.txt", "deleted query".getBytes(), "text/plain");
        String readToken = signReadToken(TENANT_ID, fileId, Instant.now().plusSeconds(60));
        String deleteToken = signDeleteToken(TENANT_ID, fileId, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", deleteToken)
                .when()
                .delete("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("success", equalTo(true));

        given()
                .queryParam("access_token", readToken)
                .when()
                .get("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(404)
                .body("success", equalTo(false))
                .body("message", equalTo("file not found"));
    }

    @Test
    void shouldDeleteFileWithValidDeleteToken() throws Exception {
        String fileId = uploadSingleFile("delete.txt", "delete me".getBytes(), "text/plain");
        String accessToken = signDeleteToken(TENANT_ID, fileId, Instant.now().plusSeconds(30));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .delete("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.id", equalTo(fileId))
                .body("data.status", equalTo("DELETED"));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .delete("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(404)
                .body("success", equalTo(false))
                .body("message", equalTo("file not found"));
    }

    @Test
    void shouldRejectExpiredDeleteToken() throws Exception {
        String fileId = uploadSingleFile("expired-delete.txt", "expired delete".getBytes(), "text/plain");
        String accessToken = signDeleteToken(TENANT_ID, fileId, Instant.now().minusSeconds(5));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .delete("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(401)
                .body("success", equalTo(false))
                .body("message", equalTo("download token expired"));
    }

    @Test
    void shouldRejectDeleteTokenForTenantMismatch() throws Exception {
        String fileId = uploadSingleFile("tenant-mismatch-delete.txt", "delete".getBytes(), "text/plain");
        String accessToken = signDeleteToken("tenant-b", fileId, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .delete("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(403)
                .body("success", equalTo(false))
                .body("message", equalTo("download token is not valid for current tenant"));
    }

    @Test
    void shouldRejectInvalidFileIdForPublicDelete() throws Exception {
        String accessToken = signDeleteToken(TENANT_ID, ULID_GENERATOR.nextULID(), Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .delete("/api/v1/public/files/not-a-ulid")
                .then()
                .statusCode(400)
                .body("success", equalTo(false))
                .body("message", equalTo("invalid fileId"));
    }

    @Test
    void shouldReturnNotFoundForQueryAndDownloadAfterDeleteByToken() throws Exception {
        String fileId = uploadSingleFile("delete-linked.txt", "delete linked".getBytes(), "text/plain");
        String readToken = signReadToken(TENANT_ID, fileId, Instant.now().plusSeconds(60));
        String deleteToken = signDeleteToken(TENANT_ID, fileId, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", deleteToken)
                .when()
                .delete("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(200)
                .body("success", equalTo(true));

        given()
                .queryParam("access_token", readToken)
                .when()
                .get("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(404)
                .body("success", equalTo(false))
                .body("message", equalTo("file not found"));

        given()
                .queryParam("access_token", readToken)
                .when()
                .get("/api/v1/public/files/{fileId}/download", fileId)
                .then()
                .statusCode(404)
                .body("success", equalTo(false))
                .body("message", equalTo("file not found"));
    }

    @Test
    void shouldRejectReadTokenForDelete() throws Exception {
        String fileId = uploadSingleFile("protected-delete.txt", "delete".getBytes(), "text/plain");
        String accessToken = signReadToken(TENANT_ID, fileId, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .delete("/api/v1/public/files/{fileId}", fileId)
                .then()
                .statusCode(403)
                .body("success", equalTo(false))
                .body("message", equalTo("download token purpose is not valid for delete"));
    }

    @Test
    void shouldPreviewFileByToken() throws Exception {
        String fileId = uploadSingleFile("token-preview.pdf",
                "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8),
                "application/pdf");
        String accessToken = signReadToken(TENANT_ID, fileId, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .redirects().follow(false)
                .when()
                .get("/api/v1/public/files/{fileId}/preview", fileId)
                .then()
                .statusCode(302)
                .header("Location", Matchers.startsWith("preview/content?access_token="));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .get("/api/v1/public/files/{fileId}/preview/content", fileId)
                .then()
                .statusCode(200)
                .header("Content-Type", Matchers.containsString("application/pdf"))
                .header("Content-Disposition", Matchers.containsString("inline;"));
    }

    @Test
    void shouldRejectPreviewTokenForAnotherFile() throws Exception {
        String firstFileId = uploadSingleFile("a.pdf", "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8), "application/pdf");
        String secondFileId = uploadSingleFile("b.pdf", "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8), "application/pdf");
        String accessToken = signReadToken(TENANT_ID, firstFileId, Instant.now().plusSeconds(60));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .get("/api/v1/public/files/{fileId}/preview", secondFileId)
                .then()
                .statusCode(403)
                .body("success", equalTo(false))
                .body("message", equalTo("download token does not match requested file"));
    }

    @Test
    void shouldRejectExpiredPreviewToken() throws Exception {
        String fileId = uploadSingleFile("expired-preview.pdf",
                "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8),
                "application/pdf");
        String accessToken = signReadToken(TENANT_ID, fileId, Instant.now().minusSeconds(5));

        given()
                .queryParam("access_token", accessToken)
                .when()
                .get("/api/v1/public/files/{fileId}/preview", fileId)
                .then()
                .statusCode(401)
                .body("success", equalTo(false))
                .body("message", equalTo("download token expired"));
    }

    @Test
    void shouldDeleteGeneratedPreviewWhenFileIsDeleted() throws Exception {
        String fileId = uploadSingleFile(
                "delete-preview.docx",
                minimalDocxBytes(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}/preview/content", fileId)
                .then()
                .statusCode(200);

        Path previewPath = previewStoragePath(fileId);
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(previewPath));

        givenAuthenticated()
                .when()
                .delete("/api/v1/files/{fileId}", fileId)
                .then()
                .statusCode(200);

        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(previewPath));
    }

    @Test
    void shouldCleanupGeneratedPreviewForTemporaryFile() throws Exception {
        String fileId = uploadSingleFile(
                "cleanup-preview.docx",
                minimalDocxBytes(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                true
        );

        givenAuthenticated()
                .when()
                .get("/api/v1/files/{fileId}/preview/content", fileId)
                .then()
                .statusCode(200);

        Path previewPath = previewStoragePath(fileId);
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(previewPath));

        ageTemporaryFile(fileId, Instant.now().minusSeconds(60L * 60L * 25L));
        runCleanupJob();

        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(previewPath));
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
        return uploadSingleFile(filename, content, contentType, false);
    }

    private String uploadSingleFile(String filename, byte[] content, String contentType, boolean temporary) {
        return givenAuthenticated()
                .multiPart("files", filename, content, contentType)
                .multiPart("temporary", String.valueOf(temporary))
                .when()
                .post("/api/v1/files")
                .then()
                .statusCode(200)
                .extract()
                .path("data.items[0].id");
    }

    private Path storagePath(String fileId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return STORAGE_ROOT.resolve(TENANT_ID)
                .resolve("%04d".formatted(today.getYear()))
                .resolve("%02d".formatted(today.getMonthValue()))
                .resolve(fileId);
    }

    private Path previewStoragePath(String fileId) {
        return STORAGE_ROOT.resolve(TENANT_ID)
                .resolve("previews")
                .resolve(fileId)
                .resolve("preview.pdf");
    }

    private String signReadToken(String tenantId, String fileId, Instant expiresAt) throws Exception {
        return signToken("""
                {"iss":"biz-app","sub":"%s","tenant_id":"%s","file_id":"%s","exp":%d}
                """.formatted(USER_ID, tenantId, fileId, expiresAt.getEpochSecond()).trim(), TOKEN_SECRET);
    }

    private String signDeleteToken(String tenantId, String fileId, Instant expiresAt) throws Exception {
        return signToken("""
                {"iss":"biz-app","sub":"%s","purpose":"delete","tenant_id":"%s","file_id":"%s","exp":%d}
                """.formatted(USER_ID, tenantId, fileId, expiresAt.getEpochSecond()).trim(), TOKEN_SECRET);
    }

    private String signUploadToken(String tenantId, String userId, Instant expiresAt) throws Exception {
        return signUploadToken("""
                {"iss":"biz-app","sub":"%s","purpose":"upload","tenant_id":"%s","exp":%d}
                """.formatted(userId, tenantId, expiresAt.getEpochSecond()).trim());
    }

    private String signUploadToken(String payload) throws Exception {
        return signToken(payload, TOKEN_SECRET);
    }

    private String signToken(String payload, String secret) throws Exception {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = mac.doFinal(payloadBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private void ageTemporaryFile(String fileId, Instant uploadedAt) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update file_metadata set uploaded_at = ? where id = ?"
             )) {
            statement.setString(1, uploadedAt.toString());
            statement.setString(2, fileId);
            statement.executeUpdate();
        }
    }

    private void runCleanupJob() throws Exception {
        fileCleanupJob.sweepNow();
    }

    private byte[] minimalDocxBytes() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeZipEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            writeZipEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """);
            writeZipEntry(zip, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p>
                          <w:r>
                            <w:t>Fake preview</w:t>
                          </w:r>
                        </w:p>
                      </w:body>
                    </w:document>
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to build minimal docx", exception);
        }
        return output.toByteArray();
    }

    private void writeZipEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
