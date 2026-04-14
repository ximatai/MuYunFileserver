package net.ximatai.muyun.fileserver.application;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.exception.ConflictException;
import net.ximatai.muyun.fileserver.common.exception.UnsupportedMediaTypeException;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageKeyFactory;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;
import net.ximatai.muyun.fileserver.infrastructure.ulid.UlidGenerator;
import org.jboss.resteasy.reactive.server.multipart.FileItem;
import org.jboss.resteasy.reactive.server.multipart.FormValue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadFilePreparerTest {

    @Test
    void shouldPrepareFileWithGeneratedIdAndMetadata() throws Exception {
        UploadFilePreparer preparer = new UploadFilePreparer();
        preparer.config = TestConfigs.fileServiceConfig();
        preparer.repository = new InMemoryRepository(Set.of());
        preparer.storageProvider = new TestStorageProvider(Files.createTempDirectory("upload-preparer-test"));
        preparer.storageKeyFactory = new StorageKeyFactory();
        preparer.ulidGenerator = new FixedUlidGenerator("01JABCDEF1234567890ABCDEF", Set.of("01JABCDEF1234567890ABCDEF"));
        preparer.supportedFileTypes = new SupportedFileTypes();

        UploadRequest request = new UploadRequest(
                List.of(TestFormValue.file("hello.txt", "hello".getBytes(), "text/plain")),
                List.of(),
                "remark",
                false
        );

        List<PreparedUpload> preparedUploads = preparer.prepare(request, new RequestContext("tenant-a", "user-1", "req-1", "client-1"));

        assertEquals(1, preparedUploads.size());
        PreparedUpload preparedUpload = preparedUploads.getFirst();
        assertEquals("01JABCDEF1234567890ABCDEF", preparedUpload.fileId());
        assertEquals("hello.txt", preparedUpload.originalFilename());
        assertEquals("txt", preparedUpload.extension());
        assertEquals("text/plain", preparedUpload.mimeType());
        assertEquals(5L, preparedUpload.sizeBytes());
        assertTrue(preparedUpload.storageKey().startsWith("tenant-a/"));
        assertTrue(preparedUpload.storageKey().endsWith("/01JABCDEF1234567890ABCDEF"));
        assertNotNull(preparedUpload.sha256());
        assertEquals(false, preparedUpload.temporary());
        assertTrue(Files.exists(preparedUpload.tempFile()));
    }

    @Test
    void shouldRejectConflictingExplicitFileId() throws Exception {
        UploadFilePreparer preparer = new UploadFilePreparer();
        preparer.config = TestConfigs.fileServiceConfig();
        preparer.repository = new InMemoryRepository(Set.of("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
        preparer.storageProvider = new TestStorageProvider(Files.createTempDirectory("upload-preparer-conflict"));
        preparer.storageKeyFactory = new StorageKeyFactory();
        preparer.ulidGenerator = new FixedUlidGenerator("generated", Set.of("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
        preparer.supportedFileTypes = new SupportedFileTypes();

        UploadRequest request = new UploadRequest(
                List.of(TestFormValue.file("hello.txt", "hello".getBytes(), "text/plain")),
                List.of("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                null,
                false
        );

        ConflictException exception = assertThrows(ConflictException.class,
                () -> preparer.prepare(request, new RequestContext("tenant-a", "user-1", "req-1", "client-1")));

        assertEquals("file_id already exists", exception.getMessage());
    }

    @Test
    void shouldRejectInvalidGeneratedOrRequestedFileId() throws Exception {
        UploadFilePreparer preparer = new UploadFilePreparer();
        preparer.config = TestConfigs.fileServiceConfig();
        preparer.repository = new InMemoryRepository(Set.of());
        preparer.storageProvider = new TestStorageProvider(Files.createTempDirectory("upload-preparer-invalid"));
        preparer.storageKeyFactory = new StorageKeyFactory();
        preparer.ulidGenerator = new FixedUlidGenerator("bad-ulid", Set.of("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
        preparer.supportedFileTypes = new SupportedFileTypes();

        UploadRequest request = new UploadRequest(
                List.of(TestFormValue.file("hello.txt", "hello".getBytes(), "text/plain")),
                List.of(),
                null,
                false
        );

        ValidationException exception = assertThrows(ValidationException.class,
                () -> preparer.prepare(request, new RequestContext("tenant-a", "user-1", "req-1", "client-1")));

        assertEquals("invalid file_id", exception.getMessage());
    }

    @Test
    void shouldRejectUnsupportedMimeType() throws Exception {
        UploadFilePreparer preparer = new UploadFilePreparer();
        preparer.config = TestConfigs.fileServiceConfig();
        preparer.repository = new InMemoryRepository(Set.of());
        preparer.storageProvider = new TestStorageProvider(Files.createTempDirectory("upload-preparer-mime"));
        preparer.storageKeyFactory = new StorageKeyFactory();
        preparer.ulidGenerator = new FixedUlidGenerator("01ARZ3NDEKTSV4RRFFQ69G5FAV", Set.of("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
        preparer.supportedFileTypes = new SupportedFileTypes();

        UploadRequest request = new UploadRequest(
                List.of(TestFormValue.file("script.sh", "echo hi".getBytes(), "application/x-sh")),
                List.of(),
                null,
                false
        );

        UnsupportedMediaTypeException exception = assertThrows(UnsupportedMediaTypeException.class,
                () -> preparer.prepare(request, new RequestContext("tenant-a", "user-1", "req-1", "client-1")));

        assertEquals("unsupported media type", exception.getMessage());
    }

    @Test
    void shouldCanonicalizeSupportedMimeAlias() throws Exception {
        UploadFilePreparer preparer = new UploadFilePreparer();
        preparer.config = TestConfigs.fileServiceConfig();
        preparer.repository = new InMemoryRepository(Set.of());
        preparer.storageProvider = new TestStorageProvider(Files.createTempDirectory("upload-preparer-alias"));
        preparer.storageKeyFactory = new StorageKeyFactory();
        preparer.ulidGenerator = new FixedUlidGenerator("01JABCDEF1234567890ABCDE1", Set.of("01JABCDEF1234567890ABCDE1"));
        preparer.supportedFileTypes = new SupportedFileTypes();

        UploadRequest request = new UploadRequest(
                List.of(TestFormValue.file("sound.wav", minimalWavBytes(), "audio/x-wav")),
                List.of(),
                null,
                false
        );

        List<PreparedUpload> preparedUploads = preparer.prepare(request, new RequestContext("tenant-a", "user-1", "req-1", "client-1"));

        assertEquals(1, preparedUploads.size());
        assertEquals("audio/wav", preparedUploads.getFirst().mimeType());
    }

    private static final class InMemoryRepository implements FileMetadataRepository {
        private final Set<String> existingIds;

        private InMemoryRepository(Set<String> existingIds) {
            this.existingIds = existingIds;
        }

        @Override
        public boolean existsById(String fileId) {
            return existingIds.contains(fileId);
        }

        @Override
        public void insert(FileMetadata metadata) {
        }

        @Override
        public Optional<FileMetadata> findById(String fileId) {
            return Optional.empty();
        }

        @Override
        public Optional<FileMetadata> findActiveById(String fileId) {
            return Optional.empty();
        }

        @Override
        public boolean promote(String fileId, String tenantId) {
            return false;
        }

        @Override
        public boolean rename(String fileId, String tenantId, String originalFilename, String extension) {
            return false;
        }

        @Override
        public boolean softDelete(String fileId, String tenantId, String deletedBy, Instant deletedAt) {
            return false;
        }

        @Override
        public boolean markTemporaryForCleanup(String fileId, Instant deletedAt, String deletedBy) {
            return false;
        }

        @Override
        public List<FileMetadata> findTemporaryBefore(Instant cutoff, int limit) {
            return List.of();
        }

        @Override
        public List<FileMetadata> findDeletedBefore(Instant cutoff, int limit) {
            return List.of();
        }

        @Override
        public void deleteById(String fileId) {
        }
    }

    private static final class FixedUlidGenerator implements UlidGenerator {
        private final String nextUlid;
        private final Set<String> validIds;

        private FixedUlidGenerator(String nextUlid, Set<String> validIds) {
            this.nextUlid = nextUlid;
            this.validIds = validIds;
        }

        @Override
        public String nextUlid() {
            return nextUlid;
        }

        @Override
        public boolean isValid(String value) {
            return validIds.contains(value);
        }
    }

    private static final class TestStorageProvider implements StorageProvider {
        private final Path tempDir;

        private TestStorageProvider(Path tempDir) {
            this.tempDir = tempDir;
        }

        @Override
        public String providerName() {
            return "test";
        }

        @Override
        public String storageBucket() {
            return null;
        }

        @Override
        public void verifyReadiness() {
        }

        @Override
        public Path createTempFile() {
            try {
                return Files.createTempFile(tempDir, "upload-", ".tmp");
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }

        @Override
        public void moveToPermanent(Path tempFile, String storageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream open(String storageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteIfExists(String storageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteTempFile(Path tempFile) {
        }

        @Override
        public boolean exists(String storageKey) {
            return false;
        }
    }

    private record TestFormValue(String value, String fileName, FileItem fileItem, MultivaluedMap<String, String> headers)
            implements FormValue {

        static TestFormValue file(String fileName, byte[] content, String contentType) {
            MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
            headers.add(HttpHeaders.CONTENT_TYPE, contentType);
            return new TestFormValue(null, fileName, new TestFileItem(content), headers);
        }

        @Override
        public String getValue() {
            return value;
        }

        @Override
        public String getCharset() {
            return null;
        }

        @Override
        public FileItem getFileItem() {
            return fileItem;
        }

        @Override
        public boolean isFileItem() {
            return fileItem != null;
        }

        @Override
        public String getFileName() {
            return fileName;
        }

        @Override
        public MultivaluedMap<String, String> getHeaders() {
            return headers;
        }
    }

    private record TestFileItem(byte[] content) implements FileItem {

        @Override
        public boolean isInMemory() {
            return true;
        }

        @Override
        public Path getFile() {
            return null;
        }

        @Override
        public long getFileSize() {
            return content.length;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void delete() {
        }

        @Override
        public void write(Path target) throws IOException {
            Files.write(target, content);
        }
    }

    private byte[] minimalWavBytes() {
        return new byte[]{
                0x52, 0x49, 0x46, 0x46,
                0x24, 0x00, 0x00, 0x00,
                0x57, 0x41, 0x56, 0x45,
                0x66, 0x6D, 0x74, 0x20,
                0x10, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x01, 0x00,
                0x40, 0x1F, 0x00, 0x00,
                (byte) 0x80, 0x3E, 0x00, 0x00,
                0x02, 0x00, 0x10, 0x00,
                0x64, 0x61, 0x74, 0x61,
                0x00, 0x00, 0x00, 0x00
        };
    }
}
