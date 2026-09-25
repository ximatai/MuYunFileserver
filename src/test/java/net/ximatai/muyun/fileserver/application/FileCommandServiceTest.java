package net.ximatai.muyun.fileserver.application;

import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.domain.file.FileStatus;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.ulid.UlidGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileCommandServiceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulDeletionSurvivesPreviewCleanupFailureAcrossBothEntrypoints(boolean token) {
        String fileId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        AtomicInteger deleted = new AtomicInteger();
        AtomicInteger cleanup = new AtomicInteger();
        FileMetadata metadata = new FileMetadata(fileId, "tenant-a", "test.docx", "docx", "application/octet-stream",
                1, "sha", "local", null, "key", FileStatus.ACTIVE, false, "user-1", Instant.EPOCH,
                null, null, null, null, null);
        FileCommandService commands = new FileCommandService();
        commands.repository = (FileMetadataRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FileMetadataRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(metadata);
                    case "softDelete" -> { deleted.incrementAndGet(); yield true; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        commands.ulidGenerator = new UlidGenerator() {
            public String nextUlid() { return fileId; }
            public boolean isValid(String value) { return fileId.equals(value); }
        };
        commands.requestContextHolder = new RequestContextHolder();
        commands.requestContextHolder.set(new RequestContext("tenant-a", "user-1", "request-1", "client-1"));
        commands.renderedPdfService = new RenderedPdfService() {
            @Override public void deleteRenderedPdfIfExists(FileMetadata value) {
                cleanup.incrementAndGet();
                throw new IllegalStateException("preview storage unavailable");
            }
        };
        TokenFileCommandService tokens = new TokenFileCommandService();
        tokens.commands = commands;
        tokens.ulidGenerator = commands.ulidGenerator;
        tokens.config = (FileServiceConfig) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FileServiceConfig.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("token")) throw new UnsupportedOperationException(method.getName());
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FileServiceConfig.Token.class},
                            (tokenProxy, tokenMethod, tokenArgs) -> {
                                if (tokenMethod.getName().equals("enabled")) return true;
                                throw new UnsupportedOperationException(tokenMethod.getName());
                            });
                });
        tokens.downloadTokenVerifier = new DownloadTokenVerifier() {
            @Override public DownloadTokenClaims verify(String value) {
                return new DownloadTokenClaims("issuer", "user-1", "delete", "tenant-a", fileId,
                        Instant.now().plusSeconds(60).getEpochSecond(), null, null);
            }
        };
        var result = token ? tokens.delete(fileId, "verified-token") : commands.delete(fileId);
        assertEquals("DELETED", result.status());
        assertEquals(1, deleted.get());
        assertEquals(1, cleanup.get());
    }
}
