package net.ximatai.muyun.fileserver.application;

import net.ximatai.muyun.fileserver.config.FileServiceConfig;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

final class TestConfigs {

    private TestConfigs() {
    }

    static FileServiceConfig fileServiceConfig() {
        FileServiceConfig.Storage storage = proxy(FileServiceConfig.Storage.class, methodName -> switch (methodName) {
            case "rootDir", "tempDir" -> Path.of(System.getProperty("java.io.tmpdir"));
            default -> throw new UnsupportedOperationException(methodName);
        });

        FileServiceConfig.Upload upload = proxy(FileServiceConfig.Upload.class, methodName -> switch (methodName) {
            case "maxFileSizeBytes" -> 1024L * 1024;
            case "maxFileCount" -> 10;
            case "minFreeSpaceBytes" -> 1L;
            default -> throw new UnsupportedOperationException(methodName);
        });

        FileServiceConfig.Database database = proxy(FileServiceConfig.Database.class, methodName -> switch (methodName) {
            case "path" -> Path.of("build/test-runtime.db");
            default -> throw new UnsupportedOperationException(methodName);
        });

        FileServiceConfig.Cleanup cleanup = proxy(FileServiceConfig.Cleanup.class, methodName -> switch (methodName) {
            case "deletedRetention" -> Duration.ofDays(7);
            case "deletedSweepInterval" -> Duration.ofHours(1);
            case "batchSize" -> 100;
            default -> throw new UnsupportedOperationException(methodName);
        });

        FileServiceConfig.Security security = proxy(FileServiceConfig.Security.class, methodName -> switch (methodName) {
            case "allowedMimeTypes" -> List.of("text/plain");
            default -> throw new UnsupportedOperationException(methodName);
        });

        return proxy(FileServiceConfig.class, methodName -> switch (methodName) {
            case "storage" -> storage;
            case "upload" -> upload;
            case "database" -> database;
            case "cleanup" -> cleanup;
            case "security" -> security;
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

    @FunctionalInterface
    private interface MethodValueResolver {
        Object resolve(String methodName);
    }
}
