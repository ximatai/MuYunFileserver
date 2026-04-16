package net.ximatai.muyun.fileserver.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "mfs")
public interface FileServiceConfig {

    Storage storage();

    Upload upload();

    Database database();

    Cleanup cleanup();

    Security security();

    Token token();

    @WithName("preview")
    RenderedPdf renderedPdf();

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

        @WithDefault("2H")
        Duration viewerLinkTtl();
    }

    interface RenderedPdf {
        @WithDefault("true")
        boolean enabled();

        @WithName("office-enabled")
        @WithDefault("true")
        boolean officeRenderingEnabled();

        @WithName("converter")
        @WithDefault("libreoffice")
        String renderer();

        Libreoffice libreoffice();

        @WithName("cache")
        ArtifactCache artifactCache();
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

    interface ArtifactCache {
        @WithName("cleanup-orphan-preview-on-file-delete")
        @WithDefault("true")
        boolean cleanupOrphanViewArtifactOnFileDelete();
    }

    interface Viewer {
        Text text();
    }

    interface Text {
        @WithDefault("1048576")
        long maxInlineBytes();
    }
}
