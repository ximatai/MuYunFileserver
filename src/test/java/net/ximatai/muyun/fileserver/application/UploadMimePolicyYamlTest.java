package net.ximatai.muyun.fileserver.application;

import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.yaml.YamlConfigSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadMimePolicyYamlTest {
    @Test void indexedHigherPriorityOverridesYamlArray() {
        var yaml = new YamlConfigSource("yaml", "mfs:\n  upload:\n    allowed-mime-types: [application/pdf, image/png]\n", 100);
        var override = new YamlConfigSource("override", java.util.Map.of("mfs.upload.allowed-mime-types[0]", "application/x-custom"), 200);
        var config = new SmallRyeConfigBuilder().withSources(yaml, override).build();
        var policy = new UploadMimePolicy(new SupportedFileTypes(), UploadMimePolicy.configuredValue(config));
        assertTrue(policy.allows("application/x-custom"));
        assertFalse(policy.allows("image/png"));
    }

    @Test void yamlArrayAndEmptyArray() {
        var yaml = new YamlConfigSource("yaml", "mfs:\n  upload:\n    allowed-mime-types: [application/pdf, image/png]\n");
        var config = new SmallRyeConfigBuilder().withSources(yaml).build();
        var policy = new UploadMimePolicy(new SupportedFileTypes(), UploadMimePolicy.configuredValue(config));
        assertTrue(policy.allows("image/png"));
        assertFalse(policy.allows("text/plain"));
        var empty = new YamlConfigSource("empty", "mfs:\n  upload:\n    allowed-mime-types: []\n");
        var emptyConfig = new SmallRyeConfigBuilder().withSources(empty).build();
        assertThrows(IllegalArgumentException.class, () -> new UploadMimePolicy(new SupportedFileTypes(), UploadMimePolicy.configuredValue(emptyConfig)));
    }
}
