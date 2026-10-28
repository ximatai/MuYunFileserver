package net.ximatai.muyun.fileserver.application;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Deployment policy for ordinary uploads; reception and viewer capabilities are separate. */
@Startup
@ApplicationScoped
public class UploadMimePolicy {
    private static final Pattern MIME = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*");
    private final SupportedFileTypes types;
    private final Set<String> configured;

    @Inject
    public UploadMimePolicy(SupportedFileTypes types) {
        this(types, configuredValue());
    }

    private static String configuredValue() {
        var config = ConfigProvider.getConfig();
        String name = "mfs.upload.allowed-mime-types";
        var value = config.getConfigValue(name);
        if (value.getRawValue() != null) {
            return value.getValue() == null ? "" : value.getValue();
        }
        // YAML sequences may also be exposed as indexed configuration properties.
        var indexed = config.getOptionalValues(name, String.class);
        return indexed.map(values -> String.join(",", values)).orElse(null);
    }

    UploadMimePolicy(SupportedFileTypes types, String value) {
        this.types = types;
        if (value == null) {
            configured = null;
            return;
        }
        String[] entries = value.split(",", -1);
        if (Arrays.stream(entries).anyMatch(entry -> !MIME.matcher(entry.trim().toLowerCase(java.util.Locale.ROOT)).matches())) {
            throw new IllegalArgumentException("mfs.upload.allowed-mime-types must contain a non-empty comma-separated list of exact MIME types");
        }
        configured = Arrays.stream(entries).map(types::canonicalize).collect(Collectors.toUnmodifiableSet());
    }

    public boolean allows(String mimeType) {
        return configured == null ? types.isAllowedUploadMimeType(mimeType) : configured.contains(types.canonicalize(mimeType));
    }
}
