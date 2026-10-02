package net.ximatai.muyun.fileserver.infrastructure.storage;

import io.minio.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import java.util.*;

/** Private storage access; callers can only use server-generated reception prefixes. */
@ApplicationScoped
public class ReceptionObjectStore {
    @Inject FileServiceConfig config;
    private volatile MinioClient client;
    public record ObjectInfo(String key,long sizeBytes,String etag) { }
    public List<ObjectInfo> list(String bucket,String prefix,int limit)throws Exception {
        var result=new ArrayList<ObjectInfo>();
        for(var value:client().listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
            var item=value.get();
            if(result.size()>=limit)break;
            result.add(new ObjectInfo(item.objectName().substring(prefix.length()),item.size(),item.etag()));
        }return result;
    }
    public void delete(String bucket,String key)throws Exception {client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());}
    public java.io.InputStream open(String bucket,String key)throws Exception {return client().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());}
    public void upload(String bucket,String key,java.nio.file.Path path)throws Exception {
        client().uploadObject(UploadObjectArgs.builder().bucket(bucket).object(key).filename(path.toString()).build());
    }
    public String bucket(){return config.reception().bucket().orElseThrow();}
    public String endpoint(){return config.storage().minio().endpoint().orElseThrow();}
    private MinioClient client() {
        if(client==null)synchronized(this){if(client==null){client=MinioClient.builder().endpoint(endpoint())
                .credentials(config.storage().minio().accessKey().orElseThrow(),config.storage().minio().secretKey().orElseThrow()).build();
            client.setTimeout(5000,30000,30000);}}
        return client;
    }
}
