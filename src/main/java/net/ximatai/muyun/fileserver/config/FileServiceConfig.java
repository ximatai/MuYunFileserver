package net.ximatai.muyun.fileserver.config;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigMapping(prefix = "mfs")
public interface FileServiceConfig {

    Storage storage();

    Upload upload();

    Database database();

    Cleanup cleanup();

    Security security();

    interface Storage {
        @NotNull
        Path rootDir();

        @NotNull
        Path tempDir();
    }

    interface Upload {
        @Min(1)
        long maxFileSizeBytes();

        @Min(1)
        int maxFileCount();

        @Min(0)
        long minFreeSpaceBytes();
    }

    interface Database {
        @NotNull
        Path path();
    }

    interface Cleanup {
        Duration deletedRetention();

        Duration deletedSweepInterval();

        @Min(1)
        int batchSize();
    }

    interface Security {
        @NotEmpty
        List<String> allowedMimeTypes();
    }
}
