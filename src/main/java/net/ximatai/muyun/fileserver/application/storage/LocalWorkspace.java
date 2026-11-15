package net.ximatai.muyun.fileserver.application.storage;

import java.nio.file.Path;

public interface LocalWorkspace {
    Path createTempFile();

    void deleteTempFile(Path path);

    Path createPdfWorkDirectory();

    void deletePdfWorkDirectory(Path directory);
}
