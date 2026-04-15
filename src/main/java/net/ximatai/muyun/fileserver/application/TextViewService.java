package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.common.exception.PayloadTooLargeException;
import net.ximatai.muyun.fileserver.common.exception.UnprocessableContentException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

@ApplicationScoped
public class TextViewService {

    private static final String ENCODING_NOT_SUPPORTED = "text content encoding is not supported for inline viewing";
    private static final String CONTENT_TOO_LARGE = "text content exceeds inline viewing limit";

    @Inject
    StorageProvider storageProvider;

    @Inject
    FileServiceConfig config;

    public DownloadFile openTextContent(FileMetadata metadata) {
        if (metadata.sizeBytes() > config.viewer().text().maxInlineBytes()) {
            throw new PayloadTooLargeException(CONTENT_TOO_LARGE);
        }
        byte[] originalBytes;
        try (InputStream inputStream = storageProvider.open(metadata.storageKey())) {
            originalBytes = inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read text content", exception);
        }

        String decodedText = decodeUtf8(originalBytes);
        byte[] normalizedBytes = decodedText.getBytes(StandardCharsets.UTF_8);
        return new DownloadFile(
                metadata.id(),
                metadata.originalFilename(),
                contentMimeType(metadata.mimeType()),
                normalizedBytes.length,
                () -> new ByteArrayInputStream(normalizedBytes)
        );
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer charBuffer = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return charBuffer.toString();
        } catch (CharacterCodingException exception) {
            throw new UnprocessableContentException(ENCODING_NOT_SUPPORTED);
        }
    }

    private String contentMimeType(String sourceMimeType) {
        if (sourceMimeType.contains("charset=")) {
            return sourceMimeType;
        }
        return sourceMimeType + "; charset=UTF-8";
    }
}
