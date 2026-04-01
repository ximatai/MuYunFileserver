package net.ximatai.muyun.fileserver.infrastructure.storage;

import java.io.InputStream;
import java.nio.file.Path;

public interface StorageProvider {

    String providerName();

    Path createTempFile();

    void moveToPermanent(Path tempFile, String storageKey);

    InputStream open(String storageKey);

    void deleteIfExists(String storageKey);

    void deleteTempFile(Path tempFile);

    boolean exists(String storageKey);

    Path resolve(String storageKey);
}
