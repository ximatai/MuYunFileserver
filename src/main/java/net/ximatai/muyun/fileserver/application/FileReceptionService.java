package net.ximatai.muyun.fileserver.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.FileReceptionRequests.*;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.exception.*;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.domain.file.*;
import net.ximatai.muyun.fileserver.infrastructure.persistence.*;
import net.ximatai.muyun.fileserver.infrastructure.storage.*;
import net.ximatai.muyun.fileserver.infrastructure.ulid.UlidGenerator;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class FileReceptionService {
    @Inject FileServiceConfig config;
    @Inject RequestContextHolder context;
    @Inject FileReceptionRepository receptions;
    @Inject FileMetadataRepository files;
    @Inject StorageProvider storage;
    @Inject ReceptionObjectStore objects;
    @Inject StorageKeyFactory keys;
    @Inject UlidGenerator ids;
    @Inject ObjectMapper json;
    @Inject SupportedFileTypes types;

    public record Snapshot(String id,String state,String bucket,String prefix,long expiresAt,
                           int maxObjects,long maxBytes,Map<String,String> assets) { }

    public String authorize(String bearer) {
        if(!config.reception().enabled() || !"minio".equalsIgnoreCase(config.storage().type()))
            throw new ServiceUnavailableException("file reception is disabled");
        if(config.reception().bucket().isEmpty()||config.reception().bucket().equals(config.storage().minio().bucket()))
            throw new ServiceUnavailableException("file reception requires a separate source bucket");
        String secret=config.reception().serviceToken().filter(v->v.length()>=24).orElseThrow(
                ()->new ServiceUnavailableException("file reception service identity is not configured"));
        String actual=bearer!=null && bearer.startsWith("Bearer ")?bearer.substring(7):"";
        if(!MessageDigest.isEqual(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new UnauthorizedException("invalid reception service identity");
        String tenant=context.getRequired().tenantId();
        if(!tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") ||
                Arrays.stream(config.reception().allowedTenants().orElse("").split(",")).map(String::trim).noneMatch(tenant::equals))
            throw new ForbiddenException("tenant is outside reception service scope");
        return tenant;
    }

    public Snapshot create(String tenant,Create request) {
        if(request==null || request.idempotencyKey()==null || !request.idempotencyKey().matches("[A-Za-z0-9_-]{1,128}")
                || request.maxObjects()<1 || request.maxObjects()>config.reception().maxObjects()
                || request.maxBytes()<1 || request.maxBytes()>config.reception().maxTotalBytes()
                || request.expiresInSeconds()<1 || request.expiresInSeconds()>604800)
            throw new ValidationException("invalid reception limits or idempotency key");
        String id=UUID.randomUUID().toString();
        var value=receptions.create(new FileReception(id,tenant,request.idempotencyKey(),"receiving/"+id.replace("-", "")+"/",
                "OPEN",request.maxObjects(),request.maxBytes(),now()+request.expiresInSeconds()*1000,null,null,null,objects.bucket(),request.expiresInSeconds()));
        if(value.maxObjects()!=request.maxObjects() || value.maxBytes()!=request.maxBytes()||value.ttlSeconds()!=request.expiresInSeconds())
            throw new ConflictException("idempotency key was used with different limits");
        return snapshot(value);
    }

    public Snapshot get(String tenant,String id){return snapshot(require(tenant,id));}

    /** Internal workers can stage derived objects without receiving privileged S3 credentials. */
    public void put(String tenant,String id,String key,java.io.InputStream input)throws Exception {
        var task=require(tenant,id);
        if(!task.state().equals("OPEN")||task.expiresAt()<=now())throw new ConflictException("reception no longer accepts writes");
        if(key==null||!key.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")||key.length()>512
                ||Arrays.stream(key.split("/")).anyMatch(v->v.equals(".")||v.equals("..")))throw new ValidationException("invalid reception key");
        Path temporary=storage.createTempFile();
        try {
            long limit=Math.min(task.maxBytes(),config.upload().maxFileSizeBytes());long size=0;long spaceChecked=0;
            try(var output=Files.newOutputStream(temporary)) {
                byte[] buffer=new byte[65536];int read;
                while((read=input.read(buffer))!=-1) {
                    size+=read;
                    if(size>limit)throw new ValidationException("reception object exceeds size limit");
                    if(spaceChecked==0||size-spaceChecked>=4194304) {
                        if(Files.getFileStore(temporary).getUsableSpace()-config.upload().minFreeSpaceBytes()<read)
                            throw new ServiceUnavailableException("insufficient reception staging space");
                        spaceChecked=size;
                    }
                    output.write(buffer,0,read);
                }
            }
            // Check again after a potentially slow upload. Confirm validates a separate immutable copy.
            task=require(tenant,id);
            if(!task.state().equals("OPEN")||task.expiresAt()<=now())throw new ConflictException("reception no longer accepts writes");
            objects.upload(task.bucket(),task.prefix()+key,temporary);
        }finally{storage.deleteTempFile(temporary);}
    }

    public List<ReceptionObjectStore.ObjectInfo> list(String tenant,String id)throws Exception {
        var value=require(tenant,id);
        if(!value.state().equals("OPEN") && !value.state().equals("CONFIRMING"))
            throw new ConflictException("reception is no longer accepting objects");
        if(value.expiresAt()<=now())throw new ConflictException("reception expired");
        var result=objects.list(value.bucket(),value.prefix(),value.maxObjects()+1);
        if(result.size()>value.maxObjects())throw new ConflictException("reception object limit exceeded");
        return result;
    }

    public Snapshot confirm(String tenant,String id,Confirm request)throws Exception {
        var value=require(tenant,id);
        var entries=validate(value,request);
        String hash=sha(json.writeValueAsBytes(entries));
        if(value.state().equals("READY")) {
            if(!hash.equals(value.manifestHash()))throw new ConflictException("reception already confirmed with another manifest");
            return snapshot(value);
        }
        String token=UUID.randomUUID().toString();
        if(!receptions.claim(id,hash,token,now(),leaseUntil()))throw new ConflictException("reception expired, busy or manifest changed");
        var prepared=new LinkedHashMap<String,FileMetadata>();
        try {
            for(var entry:entries) {
                Path temporary=storage.createTempFile();
                try {
                    if(Files.getFileStore(temporary).getUsableSpace()-config.upload().minFreeSpaceBytes()<entry.sizeBytes())
                        throw new ServiceUnavailableException("insufficient reception staging space");
                    var digest=MessageDigest.getInstance("SHA-256");long size=0;long renewed=now();
                    try(var source=objects.open(value.bucket(),value.prefix()+entry.key());var target=Files.newOutputStream(temporary)) {
                        byte[] buffer=new byte[65536];int read;
                        while((read=source.read(buffer))!=-1) {
                            size+=read;
                            if(size>entry.sizeBytes())throw new ConflictException("source object exceeds declared size");
                            digest.update(buffer,0,read);target.write(buffer,0,read);
                            if(now()-renewed>10000){receptions.renew(id,token,now(),leaseUntil());renewed=now();}
                        }
                    }
                    String checksum=HexFormat.of().formatHex(digest.digest());
                    if(size!=entry.sizeBytes() || (entry.sha256()!=null && !checksum.equals(entry.sha256())))
                        throw new ConflictException("source object size or checksum mismatch");
                    String mime;
                    try(var probe=Files.newInputStream(temporary)) {
                        mime=types.canonicalize(new org.apache.tika.Tika().detect(probe,entry.filename()));
                    }
                    // Raw indexes/segments are downloadable assets, not automatically playable HLS.
                    if(!types.isAllowedUploadMimeType(mime))mime="application/octet-stream";
                    String fileId=ids.nextUlid();String key=keys.build(tenant,fileId);
                    receptions.renew(id,token,now(),leaseUntil());
                    receptions.stage(id,token,fileId,key);
                    storage.moveToPermanent(temporary,key);
                    prepared.put(entry.key(),new FileMetadata(fileId,tenant,entry.filename(),extension(entry.filename()),
                            mime,size,checksum,storage.providerName(),storage.storageBucket(),key,FileStatus.ACTIVE,
                            false,"service:file-reception",Instant.now(),null,null,"reception:"+id,null,null));
                }finally{storage.deleteTempFile(temporary);}
            }
            receptions.publish(id,token,now(),prepared);
            return snapshot(require(tenant,id));
        } catch(Exception e) {
            // Preserve candidates after an ambiguous DB failure. Durable tracking allows later cleanup.
            try { receptions.release(id,token); }catch(Exception ignored){ }
            try {
                for(var item:receptions.staged(id,token).entrySet()) {
                    if(!files.existsById(item.getKey())) {
                        storage.deleteIfExists(item.getValue());
                        // A failed S3 call can still finish remotely. Retain its key for later sweeps.
                    } else receptions.unstage(item.getKey());
                }
            }catch(Exception ignored){/* leave durable candidates for the expiry worker */}
            throw e;
        }
    }

    public Snapshot cancel(String tenant,String id) {
        var value=require(tenant,id);
        if(!value.state().equals("CANCELLED") && !receptions.cancel(id))throw new ConflictException("cannot cancel active or completed reception");
        return snapshot(require(tenant,id));
    }

    @Scheduled(every="1h")
    public void cleanup() {
        if(!config.reception().enabled() || !"minio".equalsIgnoreCase(config.storage().type()))return;
        for(var task:receptions.expireAndFindCleanup(now())) {
            try {
                // Prefixes are generated by the server, never supplied by the caller.
                var batch=objects.list(task.bucket(),task.prefix(),1000);
                for(var item:batch)objects.delete(task.bucket(),task.prefix()+item.key());
                for(var item:receptions.staged(task.id()).entrySet()) {
                    if(!files.existsById(item.getKey())) {
                        storage.deleteIfExists(item.getValue());
                        // Keep a durable tombstone: a stale in-flight upload may arrive after deletion.
                        // Later sweeps delete it again. Only registered assets can lose their tracking.
                    } else receptions.unstage(item.getKey());
                }
                receptions.cleaned(task.id(),now()+(batch.size()==1000?60000:3600000));
            }catch(Exception e){
                org.jboss.logging.Logger.getLogger(FileReceptionService.class).warn("reception cleanup deferred: "+task.id());
                receptions.cleaned(task.id(),now()+60000);
            }
        }
    }

    private List<ObjectEntry> validate(FileReception task,Confirm request) {
        if(request==null || request.objects()==null || request.objects().isEmpty() || request.objects().size()>task.maxObjects())
            throw new ValidationException("invalid reception manifest");
        long total=0;var seen=new HashSet<String>();
        for(var item:request.objects()) {
            if(item==null || item.key()==null || !item.key().matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
                    || item.key().length()>512 || Arrays.stream(item.key().split("/")).anyMatch(v->v.equals(".")||v.equals(".."))
                    || !seen.add(item.key()) || item.filename()==null || !item.filename().matches("[\\p{L}\\p{N} _.-]{1,160}")
                    || item.sizeBytes()<0 || item.sizeBytes()>config.upload().maxFileSizeBytes()
                    || (item.sha256()!=null && !item.sha256().matches("[a-f0-9]{64}")))
                throw new ValidationException("invalid reception object");
            if(item.sizeBytes()>task.maxBytes()-total)throw new ValidationException("reception byte limit exceeded");
            total+=item.sizeBytes();
        }
        return request.objects().stream().sorted(Comparator.comparing(ObjectEntry::key)).toList();
    }
    private FileReception require(String tenant,String id) {
        var task=receptions.find(id).orElseThrow(()->new NotFoundException("reception not found"));
        if(!task.tenantId().equals(tenant))throw new ForbiddenException("reception belongs to another tenant");
        return task;
    }
    private Snapshot snapshot(FileReception task){return new Snapshot(task.id(),task.state(),task.bucket(),task.prefix(),
            task.expiresAt(),task.maxObjects(),task.maxBytes(),receptions.assets(task.id()));}
    private long now(){return System.currentTimeMillis();}
    private long leaseUntil(){return now()+Math.max(30,config.reception().leaseSeconds())*1000;}
    private String extension(String filename){int dot=filename.lastIndexOf('.');return dot<0?null:filename.substring(dot+1).toLowerCase(Locale.ROOT);}
    private String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
