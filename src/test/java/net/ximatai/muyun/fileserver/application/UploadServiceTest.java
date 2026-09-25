package net.ximatai.muyun.fileserver.application;

import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadServiceTest {
    private final RequestContext context = new RequestContext("tenant-a", "user-1", "req-1", "client-1");
    private final List<String> inserted = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private final List<String> removedStorage = new ArrayList<>();
    private final List<Path> cleaned = new ArrayList<>();

    @Test
    void cleanupFailureMustNotTurnPersistedUploadIntoFailureOrSkipRemainingCleanup() {
        var response = service(null).upload(null, context, true);

        assertEquals(List.of("first", "second"), response.items().stream().map(item -> item.id()).toList());
        assertEquals(List.of("first", "second"), inserted);
        assertEquals(List.of(), deleted);
        assertEquals(List.of(), removedStorage);
        assertEquals(List.of(Path.of("first.tmp"), Path.of("second.tmp")), cleaned);
    }

    @Test
    void cleanupFailureMustPreserveOriginalFailureAndCleanEachFileOnce() {
        RuntimeException failure = new IllegalStateException("database unavailable");

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service(failure).upload(null, context, true)));

        assertEquals(List.of("first"), inserted);
        assertEquals(List.of("first"), deleted);
        assertEquals(List.of("second", "first"), removedStorage);
        assertEquals(List.of(Path.of("first.tmp"), Path.of("second.tmp")), cleaned);
    }

    private UploadService service(RuntimeException insertFailure) {
        UploadService service = new UploadService();
        service.uploadRequestParser = new UploadRequestParser() {
            @Override
            UploadRequest parse(MultipartFormDataInput input, boolean allowRequestedFileIds) {
                return new UploadRequest(List.of(), List.of(), null, false);
            }
        };
        service.uploadFilePreparer = new UploadFilePreparer() {
            @Override
            List<PreparedUpload> prepare(UploadRequest request, RequestContext context) {
                return List.of(prepared("first"), prepared("second"));
            }
        };
        service.repository = (FileMetadataRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{FileMetadataRepository.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "insert" -> {
                            var metadata = (net.ximatai.muyun.fileserver.domain.file.FileMetadata) args[0];
                            if (insertFailure != null && metadata.id().equals("second")) throw insertFailure;
                            inserted.add(metadata.id());
                        }
                        case "deleteById" -> deleted.add((String) args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                    return null;
                });
        service.storageProvider = (StorageProvider) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{StorageProvider.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "providerName": return "test";
                        case "moveToPermanent": return null;
                        case "deleteIfExists": removedStorage.add((String) args[0]); return null;
                        case "deleteTempFile":
                            cleaned.add((Path) args[0]);
                            if (args[0].equals(Path.of("first.tmp"))) throw new IllegalStateException("cleanup unavailable");
                            return null;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        return service;
    }

    private PreparedUpload prepared(String id) {
        return new PreparedUpload(id, context.tenantId(), id + ".txt", "txt", "text/plain",
                5L, "hash", null, id, Instant.EPOCH, false, Path.of(id + ".tmp"), null, null);
    }
}
