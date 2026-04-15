package net.ximatai.muyun.fileserver.infrastructure.storage;

import java.io.InputStream;
import java.nio.file.Path;

public interface StorageProvider {

    String providerName();

    String storageBucket();

    void verifyReadiness();

    Path createTempFile();

    void moveToPermanent(Path tempFile, String storageKey);

    InputStream open(String storageKey);

    InputStream openRange(String storageKey, long start, long length);

    void deleteIfExists(String storageKey);

    void deleteTempFile(Path tempFile);

    boolean exists(String storageKey);
}
