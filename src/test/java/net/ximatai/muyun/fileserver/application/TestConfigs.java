package net.ximatai.muyun.fileserver.application;

import net.ximatai.muyun.fileserver.config.FileServiceConfig;

import java.lang.reflect.Proxy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class TestConfigs {

    private TestConfigs() {
    }

    public static FileServiceConfig fileServiceConfig() {
        return fileServiceConfig("local", null, null, null, null, "soffice");
    }

    public static FileServiceConfig fileServiceConfigWithPreviewCommand(String command) {
        return fileServiceConfig("local", null, null, null, null, command);
    }

    public static FileServiceConfig minioFileServiceConfig(String endpoint, String accessKey, String secretKey, String bucket) {
        return fileServiceConfig("minio", endpoint, accessKey, secretKey, bucket, "soffice");
    }

    private static FileServiceConfig fileServiceConfig(
            String storageType,
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket,
            String previewCommand
    ) {
        Path testRoot = Path.of(System.getProperty("user.dir"), "build", "test-config-storage");
        Path testTemp = Path.of(System.getProperty("user.dir"), "build", "test-config-tmp");
        Path previewProfileRoot = Path.of(System.getProperty("user.dir"), "build", "test-config-libreoffice-profile");
        ensureDirectory(testRoot);
        ensureDirectory(testTemp);
        ensureDirectory(previewProfileRoot);

        FileServiceConfig.Storage storage = proxy(FileServiceConfig.Storage.class, methodName -> switch (methodName) {
            case "type" -> storageType;
            case "rootDir" -> testRoot;
            case "tempDir" -> testTemp;
            case "minio" -> proxy(FileServiceConfig.Minio.class, minioMethod -> switch (minioMethod) {
                case "endpoint" -> java.util.Optional.ofNullable(endpoint);
                case "accessKey" -> java.util.Optional.ofNullable(accessKey);
                case "secretKey" -> java.util.Optional.ofNullable(secretKey);
                case "bucket" -> java.util.Optional.ofNullable(bucket);
                case "autoCreateBucket" -> true;
                default -> throw new UnsupportedOperationException(minioMethod);
            });
            default -> throw new UnsupportedOperationException(methodName);
        });

        FileServiceConfig.Upload upload = proxy(FileServiceConfig.Upload.class, methodName -> switch (methodName) {
            case "maxFileSizeBytes" -> 1024L * 1024;
            case "maxFileCount" -> 10;
            case "minFreeSpaceBytes" -> 1L;
            default -> throw new UnsupportedOperationException(methodName);
        });

        FileServiceConfig.Database database = proxy(FileServiceConfig.Database.class, methodName -> switch (methodName) {
            case "path" -> Path.of("build/test-runtime-temp.db");
            default -> throw new UnsupportedOperationException(methodName);
        });

        FileServiceConfig.Cleanup cleanup = proxy(FileServiceConfig.Cleanup.class, methodName -> switch (methodName) {
            case "temporaryRetention" -> Duration.ofHours(24);
            case "deletedRetention" -> Duration.ofDays(7);
            case "deletedSweepInterval" -> Duration.ofHours(1);
            case "batchSize" -> 100;
            default -> throw new UnsupportedOperationException(methodName);
        });

        FileServiceConfig.Security security = proxy(FileServiceConfig.Security.class, methodName -> switch (methodName) {
            case "allowedMimeTypes" -> List.of("text/plain");
            default -> throw new UnsupportedOperationException(methodName);
        });

        FileServiceConfig.Token token = proxy(FileServiceConfig.Token.class, methodName -> switch (methodName) {
            case "enabled" -> false;
            case "algorithm" -> "hmac-sha256";
            case "issuer" -> java.util.Optional.empty();
            case "secret" -> java.util.Optional.empty();
            case "allowedClockSkew" -> Duration.ofSeconds(5);
            default -> throw new UnsupportedOperationException(methodName);
        });

        FileServiceConfig.Preview preview = proxy(FileServiceConfig.Preview.class, methodName -> switch (methodName) {
            case "enabled" -> true;
            case "officeEnabled" -> true;
            case "allowedMimeTypes" -> List.of(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            );
            case "converter" -> "libreoffice";
            case "libreoffice" -> proxy(FileServiceConfig.Libreoffice.class, previewMethod -> switch (previewMethod) {
                case "command" -> previewCommand;
                case "timeout" -> Duration.ofSeconds(5);
                case "maxConcurrency" -> 1;
                case "retryFailureAfter" -> Duration.ofMinutes(5);
                case "profileRoot" -> previewProfileRoot;
                default -> throw new UnsupportedOperationException(previewMethod);
            });
            case "cache" -> proxy(FileServiceConfig.Cache.class, cacheMethod -> switch (cacheMethod) {
                case "cleanupOrphanPreviewOnFileDelete" -> true;
                default -> throw new UnsupportedOperationException(cacheMethod);
            });
            default -> throw new UnsupportedOperationException(methodName);
        });

        return proxy(FileServiceConfig.class, methodName -> switch (methodName) {
            case "storage" -> storage;
            case "upload" -> upload;
            case "database" -> database;
            case "cleanup" -> cleanup;
            case "security" -> security;
            case "token" -> token;
            case "preview" -> preview;
            default -> throw new UnsupportedOperationException(methodName);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, MethodValueResolver resolver) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + "Proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> resolver.resolve(method.getName());
                }
        );
    }

    private static void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create test directory " + path, exception);
        }
    }

    @FunctionalInterface
    private interface MethodValueResolver {
        Object resolve(String methodName);
    }
}
