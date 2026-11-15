package net.ximatai.muyun.fileserver.application.storage;

import net.ximatai.muyun.fileserver.domain.file.StoredObject;

import java.io.InputStream;
import java.nio.file.Path;

public interface AssetStorage {
    record Location(String id, String type, String bucket) {
        public StoredObject object(String key) {
            return new StoredObject(id, key);
        }
    }

    Location writeLocation();

    void write(Path staged, StoredObject object);

    InputStream open(StoredObject object);

    InputStream openRange(StoredObject object, long start, long length);

    boolean exists(StoredObject object);

    void deleteIfExists(StoredObject object);
}
