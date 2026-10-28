package net.ximatai.muyun.fileserver.application;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(UploadMimePolicyConfigTest.Profile.class)
class UploadMimePolicyConfigTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("mfs.upload.allowed-mime-types[0]", "application/pdf",
                    "mfs.upload.allowed-mime-types[1]", "application/x-custom");
        }
    }
    @Inject UploadMimePolicy policy;
    @Inject SupportedFileTypes types;
    @Test void configurationReplacesOnlyUploadPolicy() {
        assertTrue(policy.allows("application/x-custom"));
        assertFalse(policy.allows("image/png"));
        assertTrue(types.isImageMimeType("image/png"));
    }
}
