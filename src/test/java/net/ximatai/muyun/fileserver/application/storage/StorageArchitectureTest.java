package net.ximatai.muyun.fileserver.application.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class StorageArchitectureTest {
    @Test
    void storageAdaptersAndSdkStayOutsideFileWorkflows() throws IOException {
        Path root = Path.of("src/main/java/net/ximatai/muyun/fileserver");
        Path adapters = root.resolve("infrastructure/storage");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!file.startsWith(adapters)) {
                    assertFalse(source.contains("io.minio"), file + " must use a storage interface");
                }
                if (file.startsWith(root.resolve("application")) || file.startsWith(root.resolve("domain"))
                        || file.startsWith(root.resolve("api")) || file.startsWith(root.resolve("job"))) {
                    assertFalse(source.contains("net.ximatai.muyun.fileserver.infrastructure.storage"),
                            file + " must use a storage interface");
                }
            }
        }
    }
}
