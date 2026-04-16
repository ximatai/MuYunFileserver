package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SupportedFileTypes {

    private static final Map<String, String> MIME_ALIASES = Map.ofEntries(
            Map.entry("audio/vnd.wave", "audio/wav"),
            Map.entry("audio/wave", "audio/wav"),
            Map.entry("audio/x-wav", "audio/wav")
    );

    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain",
            "text/markdown",
            "text/csv",
            "text/xml",
            "application/json",
            "application/xml"
    );

    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/bmp",
            "image/gif",
            "image/heic",
            "image/heif",
            "image/jpeg",
            "image/png",
            "image/svg+xml",
            "image/tiff",
            "image/webp"
    );

    private static final Set<String> AUDIO_MIME_TYPES = Set.of(
            "audio/aac",
            "audio/mpeg",
            "audio/mp4",
            "audio/ogg",
            "audio/wav",
            "audio/webm"
    );

    private static final Set<String> VIDEO_MIME_TYPES = Set.of(
            "video/mp4",
            "video/ogg",
            "video/quicktime",
            "video/webm",
            "video/x-msvideo"
    );

    private static final Set<String> ARCHIVE_MIME_TYPES = Set.of(
            "application/gzip",
            "application/x-7z-compressed",
            "application/x-bzip2",
            "application/x-rar-compressed",
            "application/x-tar",
            "application/zip"
    );

    private static final Set<String> OFFICE_MIME_TYPES = Set.of(
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    private static final Set<String> DOCUMENT_MIME_TYPES = Set.of(
            "application/pdf",
            "application/rtf"
    );

    private static final Set<String> ALLOWED_UPLOAD_MIME_TYPES = Set.<String>of(
            "application/pdf",
            "application/rtf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/csv",
            "text/markdown",
            "text/xml",
            "application/json",
            "application/xml",
            "image/bmp",
            "image/gif",
            "image/heic",
            "image/heif",
            "image/jpeg",
            "image/png",
            "image/svg+xml",
            "image/tiff",
            "image/webp",
            "audio/aac",
            "audio/mpeg",
            "audio/mp4",
            "audio/ogg",
            "audio/wav",
            "audio/webm",
            "video/mp4",
            "video/ogg",
            "video/quicktime",
            "video/webm",
            "video/x-msvideo",
            "application/gzip",
            "application/x-7z-compressed",
            "application/x-bzip2",
            "application/x-rar-compressed",
            "application/x-tar",
            "application/zip"
    );

    public String canonicalize(String mimeType) {
        String normalized = normalize(mimeType);
        return MIME_ALIASES.getOrDefault(normalized, normalized);
    }

    public boolean isAllowedUploadMimeType(String mimeType) {
        return ALLOWED_UPLOAD_MIME_TYPES.contains(canonicalize(mimeType));
    }

    public boolean supportsRenderedPdfMimeType(String mimeType) {
        String canonical = canonicalize(mimeType);
        return "application/pdf".equals(canonical) || OFFICE_MIME_TYPES.contains(canonical);
    }

    public boolean isOfficeMimeType(String mimeType) {
        return OFFICE_MIME_TYPES.contains(canonicalize(mimeType));
    }

    public boolean isImageMimeType(String mimeType) {
        return IMAGE_MIME_TYPES.contains(canonicalize(mimeType));
    }

    public boolean isTextMimeType(String mimeType) {
        return TEXT_MIME_TYPES.contains(canonicalize(mimeType));
    }

    public boolean isAudioMimeType(String mimeType) {
        return AUDIO_MIME_TYPES.contains(canonicalize(mimeType));
    }

    public boolean isVideoMimeType(String mimeType) {
        return VIDEO_MIME_TYPES.contains(canonicalize(mimeType));
    }

    public boolean isDocumentMimeType(String mimeType) {
        String canonical = canonicalize(mimeType);
        return DOCUMENT_MIME_TYPES.contains(canonical) || OFFICE_MIME_TYPES.contains(canonical);
    }

    public boolean isArchiveMimeType(String mimeType) {
        return ARCHIVE_MIME_TYPES.contains(canonicalize(mimeType));
    }

    private String normalize(String mimeType) {
        return mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
    }
}
