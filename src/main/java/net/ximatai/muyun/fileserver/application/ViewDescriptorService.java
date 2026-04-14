package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.FileViewResponse;
import net.ximatai.muyun.fileserver.api.dto.ViewCapabilitiesResponse;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ViewDescriptorService {

    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final ViewCapabilitiesResponse PDF_CAPABILITIES =
            new ViewCapabilitiesResponse(true, true, true);
    private static final ViewCapabilitiesResponse IMAGE_CAPABILITIES =
            new ViewCapabilitiesResponse(true, true, false);
    private static final ViewCapabilitiesResponse TEXT_CAPABILITIES =
            new ViewCapabilitiesResponse(true, false, false);
    private static final ViewCapabilitiesResponse FALLBACK_CAPABILITIES =
            new ViewCapabilitiesResponse(true, false, false);
    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/bmp",
            "image/gif",
            "image/jpeg",
            "image/png",
            "image/svg+xml",
            "image/webp"
    );
    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain",
            "text/markdown",
            "text/csv",
            "text/xml",
            "application/json",
            "application/xml"
    );

    @Inject
    FileServiceConfig config;

    @Inject
    PreviewService previewService;

    public FileViewResponse describeInternal(FileMetadata metadata) {
        ViewerType viewerType = resolveViewerType(metadata.mimeType());
        if (viewerType == ViewerType.PDF) {
            previewService.ensurePreviewReady(metadata);
        }
        String contentUrl = switch (viewerType) {
            case PDF, IMAGE, TEXT -> "/api/v1/files/" + metadata.id() + "/view/content";
            case FALLBACK -> null;
        };
        return new FileViewResponse(
                metadata.id(),
                metadata.originalFilename(),
                viewerType,
                metadata.mimeType(),
                resolveContentMimeType(viewerType, metadata.mimeType()),
                contentUrl,
                "/api/v1/files/" + metadata.id() + "/download",
                resolveCapabilities(viewerType)
        );
    }

    public FileViewResponse describePublic(FileMetadata metadata, String accessToken) {
        ViewerType viewerType = resolveViewerType(metadata.mimeType());
        if (viewerType == ViewerType.PDF) {
            previewService.ensurePreviewReady(metadata);
        }

        String encodedToken = URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
        String query = "?access_token=" + encodedToken;
        String contentUrl = switch (viewerType) {
            case PDF, IMAGE, TEXT -> "/api/v1/public/files/" + metadata.id() + "/view/content/" + accessToken;
            case FALLBACK -> null;
        };
        return new FileViewResponse(
                metadata.id(),
                metadata.originalFilename(),
                viewerType,
                metadata.mimeType(),
                resolveContentMimeType(viewerType, metadata.mimeType()),
                contentUrl,
                "/api/v1/public/files/" + metadata.id() + "/download" + query,
                resolveCapabilities(viewerType)
        );
    }

    ViewerType resolveViewerType(String mimeType) {
        String normalizedMimeType = mimeType.toLowerCase(Locale.ROOT);
        if (IMAGE_MIME_TYPES.contains(normalizedMimeType)) {
            return ViewerType.IMAGE;
        }
        if (TEXT_MIME_TYPES.contains(normalizedMimeType)) {
            return ViewerType.TEXT;
        }
        if (!config.preview().enabled()) {
            return ViewerType.FALLBACK;
        }
        Set<String> allowedMimeTypes = config.preview().allowedMimeTypes().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (allowedMimeTypes.contains(normalizedMimeType)) {
            return ViewerType.PDF;
        }
        return ViewerType.FALLBACK;
    }

    private String resolveContentMimeType(ViewerType viewerType, String sourceMimeType) {
        return switch (viewerType) {
            case PDF -> PDF_MIME_TYPE;
            case IMAGE -> sourceMimeType;
            case TEXT -> textContentMimeType(sourceMimeType);
            case FALLBACK -> sourceMimeType;
        };
    }

    private ViewCapabilitiesResponse resolveCapabilities(ViewerType viewerType) {
        return switch (viewerType) {
            case PDF -> PDF_CAPABILITIES;
            case IMAGE -> IMAGE_CAPABILITIES;
            case TEXT -> TEXT_CAPABILITIES;
            case FALLBACK -> FALLBACK_CAPABILITIES;
        };
    }

    private String textContentMimeType(String sourceMimeType) {
        if (sourceMimeType.toLowerCase(Locale.ROOT).contains("charset=")) {
            return sourceMimeType;
        }
        return sourceMimeType + "; charset=UTF-8";
    }
}
