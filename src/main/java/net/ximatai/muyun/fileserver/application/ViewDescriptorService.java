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

@ApplicationScoped
public class ViewDescriptorService {

    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final ViewCapabilitiesResponse PDF_CAPABILITIES =
            new ViewCapabilitiesResponse(true, true, true);
    private static final ViewCapabilitiesResponse IMAGE_CAPABILITIES =
            new ViewCapabilitiesResponse(true, true, false);
    private static final ViewCapabilitiesResponse MEDIA_CAPABILITIES =
            new ViewCapabilitiesResponse(true, false, false);
    private static final ViewCapabilitiesResponse TEXT_CAPABILITIES =
            new ViewCapabilitiesResponse(true, false, false);
    private static final ViewCapabilitiesResponse FALLBACK_CAPABILITIES =
            new ViewCapabilitiesResponse(true, false, false);

    @Inject
    FileServiceConfig config;

    @Inject
    RenderedPdfService renderedPdfService;

    @Inject
    SupportedFileTypes supportedFileTypes;

    @Inject
    DownloadTokenSigner downloadTokenSigner;

    public FileViewResponse describeInternal(FileMetadata metadata) {
        ViewerType viewerType = resolveViewerType(metadata.mimeType());
        if (viewerType == ViewerType.PDF) {
            renderedPdfService.ensureRenderedPdfReady(metadata);
        }
        String contentUrl = switch (viewerType) {
            case PDF, IMAGE, VIDEO, AUDIO, TEXT -> "/api/v1/files/" + metadata.id() + "/view/content";
            case FALLBACK -> null;
        };
        return new FileViewResponse(
                metadata.id(),
                metadata.originalFilename(),
                metadata.sizeBytes(),
                viewerType,
                metadata.mimeType(),
                resolveContentMimeType(viewerType, metadata.mimeType()),
                contentUrl,
                "/api/v1/files/" + metadata.id() + "/download",
                resolveCapabilities(viewerType)
        );
    }

    public FileViewResponse describePublic(FileMetadata metadata, DownloadTokenClaims claims) {
        ViewerType viewerType = resolveViewerType(metadata.mimeType());
        if (viewerType == ViewerType.PDF) {
            renderedPdfService.ensureRenderedPdfReady(metadata);
        }

        String derivedToken = downloadTokenSigner.signViewerToken(claims);
        String encodedToken = URLEncoder.encode(derivedToken, StandardCharsets.UTF_8);
        String query = "?access_token=" + encodedToken;
        String contentUrl = switch (viewerType) {
            case PDF, IMAGE, VIDEO, AUDIO, TEXT -> "/api/v1/public/files/" + metadata.id() + "/view/content/" + derivedToken;
            case FALLBACK -> null;
        };
        return new FileViewResponse(
                metadata.id(),
                metadata.originalFilename(),
                metadata.sizeBytes(),
                viewerType,
                metadata.mimeType(),
                resolveContentMimeType(viewerType, metadata.mimeType()),
                contentUrl,
                "/api/v1/public/files/" + metadata.id() + "/download" + query,
                resolveCapabilities(viewerType)
        );
    }

    ViewerType resolveViewerType(String mimeType) {
        String canonicalMimeType = supportedFileTypes.canonicalize(mimeType);
        if (supportedFileTypes.isImageMimeType(canonicalMimeType)) {
            return ViewerType.IMAGE;
        }
        if (supportedFileTypes.isVideoMimeType(canonicalMimeType)) {
            return ViewerType.VIDEO;
        }
        if (supportedFileTypes.isAudioMimeType(canonicalMimeType)) {
            return ViewerType.AUDIO;
        }
        if (supportedFileTypes.isTextMimeType(canonicalMimeType)) {
            return ViewerType.TEXT;
        }
        if (!config.viewer().pdfRendering().enabled()) {
            return ViewerType.FALLBACK;
        }
        if (supportedFileTypes.supportsRenderedPdfMimeType(canonicalMimeType)) {
            return ViewerType.PDF;
        }
        return ViewerType.FALLBACK;
    }

    private String resolveContentMimeType(ViewerType viewerType, String sourceMimeType) {
        return switch (viewerType) {
            case PDF -> PDF_MIME_TYPE;
            case IMAGE, VIDEO, AUDIO -> sourceMimeType;
            case TEXT -> textContentMimeType(sourceMimeType);
            case FALLBACK -> sourceMimeType;
        };
    }

    private ViewCapabilitiesResponse resolveCapabilities(ViewerType viewerType) {
        return switch (viewerType) {
            case PDF -> PDF_CAPABILITIES;
            case IMAGE -> IMAGE_CAPABILITIES;
            case VIDEO, AUDIO -> MEDIA_CAPABILITIES;
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
