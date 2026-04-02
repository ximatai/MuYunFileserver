package net.ximatai.muyun.fileserver.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "mfs")
public interface FileServiceConfig {

    Storage storage();

    Upload upload();

    Database database();

    Cleanup cleanup();

    Security security();

    DownloadToken downloadToken();

    interface Storage {
        @WithDefault("local")
        String type();

        @NotNull
        Path rootDir();

        @NotNull
        Path tempDir();

        Minio minio();
    }

    interface Minio {
        Optional<String> endpoint();

        Optional<String> accessKey();

        Optional<String> secretKey();

        Optional<String> bucket();

        @WithDefault("true")
        boolean autoCreateBucket();
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

    interface DownloadToken {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("hmac-sha256")
        String algorithm();

        Optional<String> issuer();

        Optional<String> secret();

        @WithDefault("5S")
        Duration allowedClockSkew();
    }
}
