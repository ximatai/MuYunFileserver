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

    Token token();

    Preview preview();

    Viewer viewer();

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
        Duration temporaryRetention();

        Duration deletedRetention();

        Duration deletedSweepInterval();

        @Min(1)
        int batchSize();
    }

    interface Security {
        @NotEmpty
        List<String> allowedMimeTypes();
    }

    interface Token {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("hmac-sha256")
        String algorithm();

        Optional<String> issuer();

        Optional<String> secret();

        @WithDefault("5S")
        Duration allowedClockSkew();
    }

    interface Preview {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("true")
        boolean officeEnabled();

        @NotEmpty
        List<String> allowedMimeTypes();

        @WithDefault("libreoffice")
        String converter();

        Libreoffice libreoffice();

        Cache cache();
    }

    interface Libreoffice {
        @WithDefault("soffice")
        String command();

        @WithDefault("60S")
        Duration timeout();

        @Min(1)
        int maxConcurrency();

        @WithDefault("5M")
        Duration retryFailureAfter();

        @NotNull
        Path profileRoot();
    }

    interface Cache {
        @WithDefault("true")
        boolean cleanupOrphanPreviewOnFileDelete();
    }

    interface Viewer {
        Text text();
    }

    interface Text {
        @WithDefault("1048576")
        long maxInlineBytes();
    }
}
