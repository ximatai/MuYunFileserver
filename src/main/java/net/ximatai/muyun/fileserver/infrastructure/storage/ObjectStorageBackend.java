package net.ximatai.muyun.fileserver.infrastructure.storage;

import java.io.InputStream;
import java.nio.file.Path;

interface ObjectStorageBackend {
    void initialize();

    void verifyReadiness();

    void write(Path staged, String key);

    InputStream open(String key);

    InputStream openRange(String key, long start, long length);

    boolean exists(String key);

    void deleteIfExists(String key);
}
