package net.ximatai.muyun.fileserver.application.storage;

import net.ximatai.muyun.fileserver.domain.file.StoredObject;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

public interface ReceptionStorage {
    record Target(String locationId, String bucket) { }
    record ObjectInfo(String key, long sizeBytes, String etag) { }

    Target target();

    List<ObjectInfo> list(String locationId, String prefix, int limit) throws Exception;

    InputStream open(StoredObject object) throws Exception;

    void upload(StoredObject object, Path path) throws Exception;

    void delete(StoredObject object) throws Exception;
}
