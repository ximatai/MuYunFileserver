package net.ximatai.muyun.fileserver;

import io.minio.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.application.FileReceptionService;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileReceptionRepository;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(value=SeaweedFileResourceTestResource.class,restrictToAnnotatedClass=true)
class FileReceptionResourceTest {
    static final String BASE="/api/v1/internal/receptions";
    static final String TENANT="tenant-seaweed";
    static final String TOKEN="Bearer reception-test-service-secret-only";
    @Inject DataSource database;
    @Inject FileReceptionService service;
    @Inject FileReceptionRepository repository;
    @io.quarkus.test.common.http.TestHTTPResource java.net.URI testEndpoint;

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
        request("outside-scope").get(BASE+"/missing").then().statusCode(403);
        var task=create(4,1024);
        request("tenant-reception-b").get(BASE+"/{id}",task.get("id")).then().statusCode(403);
    }
    @Test void recordingIdentityMustNotReadOrOverwriteFormalAssets()throws Exception {
        var client=MinioClient.builder().endpoint(SeaweedFileResourceTestResource.endpoint())
                .credentials("recorder-test-user","recorder-test-secret-only").build();
        byte[] raw="recorded-data".getBytes(StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder().bucket(SeaweedFileResourceTestResource.RECEPTION_BUCKET).object("receiving/test/audio.ts")
                .stream(new ByteArrayInputStream(raw),raw.length,-1).build());
        var rejected=assertThrows(io.minio.errors.ErrorResponseException.class,()->client.putObject(PutObjectArgs.builder()
                .bucket(SeaweedFileResourceTestResource.BUCKET).object("formal.ts").stream(new ByteArrayInputStream(raw),raw.length,-1).build()));
        assertEquals(403,rejected.response().code());
        assertThrows(io.minio.errors.ErrorResponseException.class,()->client.listObjects(ListObjectsArgs.builder()
                .bucket(SeaweedFileResourceTestResource.BUCKET).build()).iterator().next().get());
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
        repository.stage(id,"stale-owner",fileId,key);expire(id);
        service.cleanup();assertEquals(key,repository.staged(id).get(fileId));
        var client=MinioClient.builder().endpoint(SeaweedFileResourceTestResource.endpoint())
                .credentials(SeaweedFileResourceTestResource.ACCESS_KEY,SeaweedFileResourceTestResource.SECRET_KEY).build();
        byte[] raw="late-upload".getBytes(StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder().bucket(SeaweedFileResourceTestResource.BUCKET).object(key)
                .stream(new ByteArrayInputStream(raw),raw.length,-1).build());
        repository.cleaned(id,0);service.cleanup();
        var rejected=assertThrows(io.minio.errors.ErrorResponseException.class,()->client.statObject(StatObjectArgs.builder()
                .bucket(SeaweedFileResourceTestResource.BUCKET).object(key).build()));
        assertEquals(404,rejected.response().code());assertEquals(key,repository.staged(id).get(fileId));
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
        MinioClient.builder().endpoint(SeaweedFileResourceTestResource.endpoint())
                .credentials(SeaweedFileResourceTestResource.ACCESS_KEY,SeaweedFileResourceTestResource.SECRET_KEY).build()
                .putObject(PutObjectArgs.builder().bucket((String)task.get("bucket")).object(task.get("prefix")+key)
                        .stream(new ByteArrayInputStream(data),data.length,-1).build());
    }
    private void expire(String id) throws Exception {
        try(var c=database.getConnection();var s=c.prepareStatement("update file_reception set expires_at=0 where id=?")) {
            s.setString(1,id);s.executeUpdate();
        }
    }
}
