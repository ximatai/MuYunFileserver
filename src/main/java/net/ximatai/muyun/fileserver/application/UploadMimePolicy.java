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
        this(types, configuredValue(ConfigProvider.getConfig()));
    }

    static String configuredValue(org.eclipse.microprofile.config.Config config) {
        var smallrye = config.unwrap(io.smallrye.config.SmallRyeConfig.class);
        String name = "mfs.upload.allowed-mime-types";
        var root = smallrye.getConfigValue(name);
        var indices = new java.util.TreeMap<Integer, io.smallrye.config.ConfigValue>();
        var pattern = Pattern.compile(Pattern.quote(name) + "\\[(\\d+)\\]");
        for (String property : config.getPropertyNames()) {
            var match = pattern.matcher(property);
            if (match.matches()) indices.put(Integer.parseInt(match.group(1)), smallrye.getConfigValue(property));
        }
        var sourceRanks = new java.util.HashMap<String, Integer>();
        int rank = 0;
        for (var source : config.getConfigSources()) sourceRanks.putIfAbsent(source.getName(), rank++);
        int rootRank = root.getRawValue() == null ? Integer.MAX_VALUE : sourceRanks.getOrDefault(root.getSourceName(), Integer.MAX_VALUE);
        int indexRank = indices.values().stream().filter(v -> v.getRawValue() != null)
                .mapToInt(v -> sourceRanks.getOrDefault(v.getSourceName(), Integer.MAX_VALUE)).min().orElse(Integer.MAX_VALUE);
        if (rootRank <= indexRank && rootRank != Integer.MAX_VALUE) {
            return root.getValue() == null ? "" : root.getValue();
        }
        if (indexRank == Integer.MAX_VALUE) return null;
        return indices.values().stream().filter(v -> v.getRawValue() != null
                        && sourceRanks.getOrDefault(v.getSourceName(), Integer.MAX_VALUE) == indexRank)
                .map(v -> v.getValue() == null ? "" : v.getValue()).collect(Collectors.joining(","));
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
