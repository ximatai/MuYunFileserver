package net.ximatai.muyun.fileserver.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadMimePolicyTest {
    private final SupportedFileTypes types = new SupportedFileTypes();

    @Test void defaultsAndOverridesAreIndependentOfViewerAndReceptionTypes() {
        var defaults = new UploadMimePolicy(types, null);
        assertTrue(defaults.allows("application/vnd.android.package-archive"));
        var restricted = new UploadMimePolicy(types, " application/pdf, AUDIO/X-WAV ");
        assertTrue(restricted.allows("audio/wav"));
        assertFalse(restricted.allows("application/vnd.android.package-archive"));
        assertTrue(types.isAllowedUploadMimeType("application/vnd.android.package-archive"));
        assertTrue(types.isImageMimeType("image/png"));
        assertTrue(new UploadMimePolicy(types, "application/x-custom").allows("application/x-custom"));
    }

    @Test void rejectsEmptyWildcardsParametersAndMalformedEntries() {
        for (String value : new String[]{"", " ", "*/*", "image/*", "application/pdf,", "application/pdf; charset=utf-8", "invalid"}) {
            assertThrows(IllegalArgumentException.class, () -> new UploadMimePolicy(types, value), value);
        }
    }
}
