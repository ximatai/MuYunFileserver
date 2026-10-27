package net.ximatai.muyun.fileserver.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportedFileTypesTest {
    @Test void androidPackagesAreOpaqueDownloadsWithoutDocumentOrArchivePreview() {
        var types = new SupportedFileTypes();
        String apk = "application/vnd.android.package-archive";
        assertTrue(types.isAllowedUploadMimeType(apk));
        assertFalse(types.isDocumentMimeType(apk));
        assertFalse(types.isArchiveMimeType(apk));
        assertFalse(types.isTextMimeType(apk));
        assertFalse(types.supportsRenderedPdfMimeType(apk));
        assertFalse(types.isAllowedUploadMimeType("application/x-msdownload"));
        assertFalse(types.isAllowedUploadMimeType("application/octet-stream"));
    }
}
