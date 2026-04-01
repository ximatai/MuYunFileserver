package net.ximatai.muyun.fileserver.application;

import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import org.jboss.resteasy.reactive.server.multipart.FileItem;
import org.jboss.resteasy.reactive.server.multipart.FormValue;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadRequestParserTest {

    @Test
    void shouldParseFilesRequestedIdsAndRemark() {
        UploadRequestParser parser = new UploadRequestParser();
        parser.config = TestConfigs.fileServiceConfig();

        UploadRequest request = parser.parse(new TestMultipartInput(Map.of(
                "files", List.of(TestFormValue.file("first.txt"), TestFormValue.file("second.txt")),
                "file_ids", List.of(TestFormValue.text("01ARZ3NDEKTSV4RRFFQ69G5FAV")),
                "remark", List.of(TestFormValue.text("batch upload"))
        )));

        assertEquals(2, request.fileValues().size());
        assertEquals(List.of("01ARZ3NDEKTSV4RRFFQ69G5FAV"), request.requestedFileIds());
        assertEquals("batch upload", request.remark());
    }

    @Test
    void shouldAllowMissingOptionalRemark() {
        UploadRequestParser parser = new UploadRequestParser();
        parser.config = TestConfigs.fileServiceConfig();

        UploadRequest request = parser.parse(new TestMultipartInput(Map.of(
                "files", List.of(TestFormValue.file("first.txt"))
        )));

        assertNull(request.remark());
    }

    @Test
    void shouldRejectBlankTextField() {
        UploadRequestParser parser = new UploadRequestParser();
        parser.config = TestConfigs.fileServiceConfig();

        ValidationException exception = assertThrows(ValidationException.class, () -> parser.parse(new TestMultipartInput(Map.of(
                "files", List.of(TestFormValue.file("first.txt")),
                "file_ids", List.of(TestFormValue.text(" "))
        ))));

        assertEquals("file_ids contains blank value", exception.getMessage());
    }

    @Test
    void shouldRejectRepeatedRemark() {
        UploadRequestParser parser = new UploadRequestParser();
        parser.config = TestConfigs.fileServiceConfig();

        ValidationException exception = assertThrows(ValidationException.class, () -> parser.parse(new TestMultipartInput(Map.of(
                "files", List.of(TestFormValue.file("first.txt")),
                "remark", List.of(TestFormValue.text("one"), TestFormValue.text("two"))
        ))));

        assertEquals("remark must appear at most once", exception.getMessage());
    }

    private record TestMultipartInput(Map<String, Collection<FormValue>> values) implements MultipartFormDataInput {
        @Override
        public Map<String, Collection<FormValue>> getValues() {
            return values;
        }
    }

    private record TestFormValue(String value, String fileName, FileItem fileItem) implements FormValue {

        static TestFormValue text(String value) {
            return new TestFormValue(value, null, null);
        }

        static TestFormValue file(String fileName) {
            return new TestFormValue(null, fileName, new TestFileItem());
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
            return new MultivaluedHashMap<>();
        }
    }

    private static final class TestFileItem implements FileItem {
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
            return 0;
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public void delete() {
        }

        @Override
        public void write(Path target) {
        }
    }
}
