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
    private static final ViewCapabilitiesResponse FALLBACK_CAPABILITIES =
            new ViewCapabilitiesResponse(true, false, false);

    @Inject
    FileServiceConfig config;

    @Inject
    PreviewService previewService;

    public FileViewResponse describeInternal(FileMetadata metadata) {
        ViewerType viewerType = resolveViewerType(metadata.mimeType());
        if (viewerType == ViewerType.PDF) {
            previewService.ensurePreviewReady(metadata);
        }
        return new FileViewResponse(
                metadata.id(),
                metadata.originalFilename(),
                viewerType,
                metadata.mimeType(),
                viewerType == ViewerType.PDF ? PDF_MIME_TYPE : metadata.mimeType(),
                viewerType == ViewerType.PDF
                        ? "/api/v1/files/" + metadata.id() + "/view/content"
                        : null,
                "/api/v1/files/" + metadata.id() + "/download",
                viewerType == ViewerType.PDF ? PDF_CAPABILITIES : FALLBACK_CAPABILITIES
        );
    }

    public FileViewResponse describePublic(FileMetadata metadata, String accessToken) {
        ViewerType viewerType = resolveViewerType(metadata.mimeType());
        if (viewerType == ViewerType.PDF) {
            previewService.ensurePreviewReady(metadata);
        }

        String encodedToken = URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
        String query = "?access_token=" + encodedToken;
        return new FileViewResponse(
                metadata.id(),
                metadata.originalFilename(),
                viewerType,
                metadata.mimeType(),
                viewerType == ViewerType.PDF ? PDF_MIME_TYPE : metadata.mimeType(),
                viewerType == ViewerType.PDF
                        ? "/api/v1/public/files/" + metadata.id() + "/view/content/" + accessToken
                        : null,
                "/api/v1/public/files/" + metadata.id() + "/download" + query,
                viewerType == ViewerType.PDF ? PDF_CAPABILITIES : FALLBACK_CAPABILITIES
        );
    }

    private ViewerType resolveViewerType(String mimeType) {
        if (!config.preview().enabled()) {
            return ViewerType.FALLBACK;
        }
        Set<String> allowedMimeTypes = config.preview().allowedMimeTypes().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (allowedMimeTypes.contains(mimeType.toLowerCase(Locale.ROOT))) {
            return ViewerType.PDF;
        }
        return ViewerType.FALLBACK;
    }
}
