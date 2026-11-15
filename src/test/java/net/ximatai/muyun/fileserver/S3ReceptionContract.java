package net.ximatai.muyun.fileserver;

import io.minio.*;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.application.FileReceptionService;
import net.ximatai.muyun.fileserver.application.storage.AssetStorage;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.domain.file.StoredObject;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileReceptionRepository;
import net.ximatai.muyun.fileserver.job.FileCleanupJob;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

abstract class S3ReceptionContract {
    static final String BASE="/api/v1/internal/receptions";
    static final String TENANT="tenant-seaweed";
    static final String TOKEN="Bearer reception-test-service-secret-only";
    static final String RECORDER_ACCESS_KEY = "recorder-test-user";
    static final String RECORDER_SECRET_KEY = "recorder-test-secret-only";
    @Inject FileServiceConfig config;
    @Inject AssetStorage assetStorage;
    @Inject DataSource database;
    @Inject FileReceptionService service;
    @Inject FileReceptionRepository repository;
    @Inject FileCleanupJob cleanup;
    @Inject FileMetadataRepository files;
    @io.quarkus.test.common.http.TestHTTPResource java.net.URI testEndpoint;

    @Test void shouldUploadPreviewReadRangeAndCleanAnOrdinaryFile() throws Exception {
        byte[] data = "ordinary file".getBytes(StandardCharsets.UTF_8);
        var client = s3Client(false);
        String fileId = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "tester")
                .multiPart("files", "ordinary.txt", data, "text/plain")
                .post("/api/v1/files").then().statusCode(200).extract().path("data.items[0].id");
        given().header("X-Tenant-Id", TENANT).header("X-User-Id", "tester")
                .header("Range", "bytes=0-7").get("/api/v1/files/{id}/download", fileId)
                .then().statusCode(206).body(equalTo("ordinary"));
        given().header("X-Tenant-Id", TENANT).header("X-User-Id", "tester")
                .get("/api/v1/files/{id}/view/content", fileId).then().statusCode(200).body(equalTo("ordinary file"));
        var object = StatObjectArgs.builder().bucket(formalBucket()).object(files.findById(fileId).orElseThrow().storageKey()).build();
        assertEquals(data.length, client.statObject(object).size());
        given().header("X-Tenant-Id", TENANT).header("X-User-Id", "tester")
                .delete("/api/v1/files/{id}", fileId).then().statusCode(200);
        try (var c = database.getConnection();
             var q = c.prepareStatement("update file_metadata set deleted_at='2000-01-01T00:00:00Z' where id=?")) {
            q.setString(1, fileId);
            q.executeUpdate();
        }
        cleanup.sweepNow();
        var missing = assertThrows(io.minio.errors.ErrorResponseException.class, () -> client.statObject(object));
        assertEquals(404, missing.response().code());
    }

    @Test void shouldProcessAudioAndVideoThroughIndependentWorker()throws Exception {
        var directory=java.nio.file.Files.createTempDirectory("media-worker-it-");
        Process process=null;
        try {
            process=new ProcessBuilder("python3","workers/media-worker/acceptance.py",testEndpoint.toString(),directory.toString())
                    .redirectErrorStream(true).redirectOutput(directory.resolve("acceptance.log").toFile()).start();
            assertTrue(process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS),"worker acceptance timed out");
            assertEquals(0,process.exitValue(),java.nio.file.Files.readString(directory.resolve("acceptance.log")));
        }finally{
            if(process!=null&&process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);
            }
            try(var paths=java.nio.file.Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(path);}
        }
    }

    @Test void shouldRequireIndependentServiceIdentityAndTenantScope() {
        given().header("X-Tenant-Id",TENANT).header("X-User-Id","tester").get(BASE+"/missing").then().statusCode(401);
        request("new-business-tenant").get(BASE+"/missing").then().statusCode(404);
        var task=create(4,1024);
        request("tenant-reception-b").get(BASE+"/{id}",task.get("id")).then().statusCode(403);
    }
    @Test void newBusinessTenantRequiresNoConfigurationButCannotAccessAnotherTenantsReception() {
        String tenant="business-"+UUID.randomUUID().toString().substring(0,8);
        var body=Map.of("idempotencyKey",UUID.randomUUID().toString(),"maxObjects",4,"maxBytes",1024,"expiresInSeconds",3600);
        String id=request(tenant).body(body).post(BASE).then().statusCode(200).extract().path("data.id");
        request(tenant).get(BASE+"/{id}",id).then().statusCode(200);
        request(TENANT).get(BASE+"/{id}",id).then().statusCode(403);
        request(TENANT).get(BASE+"/{id}/objects",id).then().statusCode(403);
        request(TENANT).body(Map.of("objects",List.of())).post(BASE+"/{id}/confirm",id).then().statusCode(403);
    }
    @Test void recordingIdentityMustNotReadOrOverwriteFormalAssets()throws Exception {
        var client = s3Client(true);
        var task = create(2, 12L * 1024 * 1024);
        String id = (String) task.get("id");
        String key = "recording.ts";
        byte[] raw = new byte[6 * 1024 * 1024];
        new Random(1024).nextBytes(raw);
        client.putObject(PutObjectArgs.builder().bucket((String) task.get("bucket"))
                .object(task.get("prefix") + key)
                .stream(new ByteArrayInputStream(raw), raw.length, 5L * 1024 * 1024).build());
        request(TENANT).get(BASE + "/{id}/objects", id).then().statusCode(200)
                .body("data[0].sizeBytes", equalTo(raw.length));
        String sha256 = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
        String asset = confirm(id, List.of(Map.of("key", key, "filename", key, "sizeBytes", raw.length,
                "sha256", sha256)), 200).path("data.assets.'recording.ts'");
        assertNotNull(asset);
        String formalObject = files.findById(asset).orElseThrow().storageKey();
        var readRejected = assertThrows(io.minio.errors.ErrorResponseException.class, () -> client.getObject(
                GetObjectArgs.builder().region("us-east-1").bucket(formalBucket()).object(formalObject).build()));
        assertEquals(403, readRejected.response().code());
        var writeRejected = assertThrows(io.minio.errors.ErrorResponseException.class, () -> client.putObject(
                PutObjectArgs.builder().region("us-east-1").bucket(formalBucket()).object(formalObject)
                        .stream(new ByteArrayInputStream(raw), raw.length, -1).build()));
        assertEquals(403, writeRejected.response().code());
        assertThrows(io.minio.errors.ErrorResponseException.class, () -> client.listObjects(
                ListObjectsArgs.builder().region("us-east-1").bucket(formalBucket()).build()).iterator().next().get());
    }

    @Test void shouldArchiveMultipleObjectsIdempotentlyAndFreezeBytes() throws Exception {
        var task=create(4,1024);String id=(String)task.get("id");
        assertEquals("receiving/"+id.replace("-", "")+"/",task.get("prefix"));
        put(task,"index.m3u8","#EXTM3U\n#EXT-X-ENDLIST\n");put(task,"part.ts","media-segment");
        request(TENANT).get(BASE+"/{id}/objects",id).then().statusCode(200).body("data.size()",equalTo(2));
        var manifest=List.of(entry("index.m3u8","#EXTM3U\n#EXT-X-ENDLIST\n"),entry("part.ts","media-segment"));
        Map<String,String> assets=confirm(id,manifest,200).path("data.assets");
        assertEquals(2,assets.size());
        assertEquals(assets,confirm(id,manifest.reversed(),200).path("data.assets"));
        put(task,"part.ts","changed-after-confirmation");
        given().header("X-Tenant-Id",TENANT).header("X-User-Id","reader")
                .get("/api/v1/files/{id}/download",assets.get("part.ts")).then().statusCode(200).body(equalTo("media-segment"));
        confirm(id,List.of(entry("part.ts","media-segment")),409);
        // Clean only reception sources; registered assets survive.
        expire(id);service.cleanup();
        given().header("X-Tenant-Id",TENANT).header("X-User-Id","reader")
                .header("Range","bytes=0-4").get("/api/v1/files/{id}/download",assets.get("part.ts"))
                .then().statusCode(206).body(equalTo("media"));
    }

    @Test void shouldRollbackWholeBatchAndRetrySameFrozenManifest() throws Exception {
        var task=create(4,1024);String id=(String)task.get("id");
        put(task,"a.ts","good");put(task,"b.ts","bad");
        var manifest=List.of(entry("a.ts","good"),entry("b.ts","correct"));
        confirm(id,manifest,409);
        assertTrue(repository.assets(id).isEmpty());assertEquals(1,repository.staged(id).size(),"failed copies retain cleanup tombstones");
        confirm(id,List.of(entry("a.ts","good")),409);
        put(task,"b.ts","correct");assertEquals(2,((Map<?,?>)confirm(id,manifest,200).path("data.assets")).size());
    }

    @Test void shouldRejectTraversalDuplicatesAndCapacityOverflow() throws Exception {
        var task=create(2,6);String id=(String)task.get("id");
        confirm(id,List.of(entry("../outside","test")),400);
        confirm(id,List.of(entry("a.ts","abc"),entry("a.ts","abc")),400);
        confirm(id,List.of(entry("a.ts","abc"),entry("b.ts","abcd")),400);
        put(task,"one.ts","1");put(task,"two.ts","2");put(task,"three.ts","3");
        request(TENANT).get(BASE+"/{id}/objects",id).then().statusCode(409);
    }

    @Test void shouldRecoverExpiredLeaseAndFencePreviousOwner() throws Exception {
        var task=create(2,1024);String id=(String)task.get("id");put(task,"a.ts","test");
        assertTrue(repository.claim(id,"frozen-hash","old-owner",System.currentTimeMillis(),System.currentTimeMillis()-1));
        assertTrue(repository.claim(id,"frozen-hash","new-owner",System.currentTimeMillis(),System.currentTimeMillis()+60000));
        assertThrows(net.ximatai.muyun.fileserver.common.exception.ConflictException.class,
                ()->repository.publish(id,"old-owner",System.currentTimeMillis(),Map.of()));
        repository.release(id,"old-owner");assertEquals("new-owner",repository.find(id).orElseThrow().leaseToken());
        // A crashed worker's fixed manifest cannot be substituted by a new request.
        confirm(id,List.of(entry("a.ts","test")),409);
    }

    @Test void shouldRetainCandidateTrackingAndReclaimUploadArrivingAfterCleanup()throws Exception {
        var task=create(2,1024);String id=(String)task.get("id");
        String key="late-candidate-"+UUID.randomUUID();String fileId="unpublished-"+UUID.randomUUID();
        StoredObject location=assetStorage.writeLocation().object(key);
        repository.stage(id,"stale-owner",fileId,location);expire(id);
        service.cleanup();assertEquals(location,repository.staged(id).get(fileId));
        var client=s3Client(false);
        byte[] raw="late-upload".getBytes(StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder().bucket(formalBucket()).object(key)
                .stream(new ByteArrayInputStream(raw),raw.length,-1).build());
        repository.cleaned(id,0);service.cleanup();
        var rejected=assertThrows(io.minio.errors.ErrorResponseException.class,()->client.statObject(StatObjectArgs.builder()
                .bucket(formalBucket()).object(key).build()));
        assertEquals(404,rejected.response().code());assertEquals(location,repository.staged(id).get(fileId));
    }

    @Test void shouldCancelAndExpireWithoutDeletingOtherTasks() throws Exception {
        var cancelled=create(2,1024);var active=create(2,1024);
        put(cancelled,"a.ts","cancel");put(active,"a.ts","active");
        String id=(String)cancelled.get("id");
        request(TENANT).post(BASE+"/{id}/cancel",id).then().statusCode(200).body("data.state",equalTo("CANCELLED"));
        confirm(id,List.of(entry("a.ts","cancel")),409);expire(id);service.cleanup();
        request(TENANT).get(BASE+"/{id}/objects",active.get("id")).then().statusCode(200).body("data.size()",equalTo(1));
        expire((String)active.get("id"));service.cleanup();
        request(TENANT).get(BASE+"/{id}",active.get("id")).then().statusCode(200).body("data.state",equalTo("EXPIRED"));
    }

    @Test void shouldReuseCreationAndRejectDifferentLimits() {
        String key=UUID.randomUUID().toString();var body=Map.of("idempotencyKey",key,"maxObjects",2,"maxBytes",1024,"expiresInSeconds",3600);
        String first=request(TENANT).body(body).post(BASE).then().statusCode(200).extract().path("data.id");
        request(TENANT).body(body).post(BASE).then().statusCode(200).body("data.id",equalTo(first));
        var changed=new HashMap<>(body);changed.put("maxObjects",3);
        request(TENANT).body(changed).post(BASE).then().statusCode(409);
    }

    private io.restassured.specification.RequestSpecification request(String tenant) {
        return given().header("X-Tenant-Id",tenant).header("X-User-Id","service")
                .header("Authorization",TOKEN).contentType("application/json");
    }
    private Map<String,Object> create(int objects,long bytes) {
        return request(TENANT).body(Map.of("idempotencyKey",UUID.randomUUID().toString(),"maxObjects",objects,
                "maxBytes",bytes,"expiresInSeconds",3600)).post(BASE).then().statusCode(200).extract().path("data");
    }
    private Map<String,Object> entry(String key,String text) throws Exception {
        byte[] data=text.getBytes(StandardCharsets.UTF_8);
        return Map.of("key",key,"filename",key.replace('/','_'),"sizeBytes",data.length,"sha256",
                HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)));
    }
    private io.restassured.response.ExtractableResponse<io.restassured.response.Response> confirm(String id,List<?> entries,int status) {
        return request(TENANT).body(Map.of("objects",entries)).post(BASE+"/{id}/confirm",id).then().statusCode(status).extract();
    }
    private void put(Map<String,Object> task,String key,String text) throws Exception {
        byte[] data=text.getBytes(StandardCharsets.UTF_8);
        s3Client(true)
                .putObject(PutObjectArgs.builder().bucket((String)task.get("bucket")).object(task.get("prefix")+key)
                        .stream(new ByteArrayInputStream(data),data.length,-1).build());
    }
    private MinioClient s3Client(boolean recorder) {
        var s3 = "s3".equals(config.storage().type()) ? config.storage().s3() : config.storage().minio();
        return MinioClient.builder().endpoint(s3.endpoint().orElseThrow())
                .credentials(recorder ? RECORDER_ACCESS_KEY : s3.accessKey().orElseThrow(),
                        recorder ? RECORDER_SECRET_KEY : s3.secretKey().orElseThrow())
                .build();
    }

    private String formalBucket() {
        return assetStorage.writeLocation().bucket();
    }

    private void expire(String id) throws Exception {
        try(var c=database.getConnection();var s=c.prepareStatement("update file_reception set expires_at=0 where id=?")) {
            s.setString(1,id);s.executeUpdate();
        }
    }
}
