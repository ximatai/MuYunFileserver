package net.ximatai.muyun.fileserver.infrastructure.storage;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.common.exception.StorageException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class StorageLocations {
    public static final String LEGACY_ASSETS = "legacy-assets";
    public static final String LEGACY_RECEPTION = "legacy-reception";

    public enum Role { ASSET, RECEPTION }

    public record Identity(String id, Role role, String namespace) { }

    public record Location(String id, Role role, String type, String bucket) { }

    private record Binding(Location location, String namespace, Path localRoot, ObjectStorageBackend backend) { }

    @Inject FileServiceConfig config;

    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private String defaultAssets;
    private String defaultReception;
    private String legacyAssets;
    private String legacyReception;

    @PostConstruct
    void configure() {
        var storage = config.storage();
        var reception = config.reception();
        if (storage.locations().isEmpty()) {
            if (storage.defaultLocation().isPresent() || storage.legacyAssetLocation().isPresent()
                    || storage.legacyReceptionLocation().isPresent() || reception.location().isPresent()) {
                throw configuration("named location settings require storage.locations");
            }
            defaultAssets = legacyAssets = LEGACY_ASSETS;
            switch (storage.type().toLowerCase(Locale.ROOT)) {
                case "local" -> addLocal(defaultAssets, storage.rootDir());
                case "minio", "s3" -> {
                    var s3 = "minio".equalsIgnoreCase(storage.type()) ? storage.minio() : storage.s3();
                    addS3(defaultAssets, Role.ASSET, s3, required(s3.bucket(), "S3 asset bucket"), s3.autoCreateBucket());
                    reception.bucket().ifPresent(bucket -> {
                        defaultReception = legacyReception = LEGACY_RECEPTION;
                        addS3(defaultReception, Role.RECEPTION, s3, bucket, false);
                    });
                }
                default -> throw configuration("unknown storage type " + storage.type());
            }
        } else {
            storage.locations().forEach((id, definition) -> {
                if (!id.matches("[a-z][a-z0-9-]{0,63}")) {
                    throw configuration("invalid location ID " + id);
                }
                Role role = switch (definition.role()) {
                    case "asset" -> Role.ASSET;
                    case "reception" -> Role.RECEPTION;
                    default -> throw configuration("invalid role for location " + id);
                };
                switch (definition.type()) {
                    case "local" -> {
                        if (role != Role.ASSET) {
                            throw configuration("local location " + id + " can only store assets");
                        }
                        addLocal(id, required(definition.rootDir(), "root-dir for " + id));
                    }
                    case "s3" -> {
                        var s3 = definition.s3();
                        addS3(id, role, s3, required(s3.bucket(), "bucket for " + id), s3.autoCreateBucket());
                    }
                    default -> throw configuration("invalid type for location " + id);
                }
            });
            defaultAssets = required(storage.defaultLocation(), "storage.default-location");
            defaultReception = reception.location().orElse(null);
            legacyAssets = storage.legacyAssetLocation().orElse(null);
            legacyReception = storage.legacyReceptionLocation().orElse(null);
        }
        require(defaultAssets, Role.ASSET);
        if (defaultReception != null || reception.enabled()) {
            require(defaultReception, Role.RECEPTION);
        }
        if (legacyAssets != null) {
            require(legacyAssets, Role.ASSET);
        }
        if (legacyReception != null) {
            require(legacyReception, Role.RECEPTION);
        }
    }

    void initialize(@Observes StartupEvent ignored) {
        bindings.values().forEach(value -> value.backend().initialize());
    }

    public List<Identity> identities() {
        return bindings.values().stream()
                .map(binding -> new Identity(binding.location().id(), binding.location().role(), binding.namespace()))
                .toList();
    }

    public Location defaultAssets() {
        return require(defaultAssets, Role.ASSET);
    }

    public Location defaultReception() {
        return require(defaultReception, Role.RECEPTION);
    }

    public String legacyAssets() {
        return legacyAssets;
    }

    public String legacyReception() {
        return legacyReception;
    }

    public Location require(String id, Role role) {
        Binding binding = id == null ? null : bindings.get(id);
        if (binding == null || binding.location().role() != role) {
            throw new StorageException("unavailable " + role.name().toLowerCase(Locale.ROOT) + " storage location: " + id);
        }
        return binding.location();
    }

    ObjectStorageBackend backend(String id, Role role) {
        return bindings.get(require(id, role).id()).backend();
    }

    public void verifyReadiness() {
        for (Binding binding : bindings.values()) {
            if (binding.location().role() == Role.ASSET) {
                binding.backend().verifyReadiness();
            }
        }
    }

    void validateWorkspace() {
        Path workspace = canonicalPath(config.storage().tempDir());
        for (Binding binding : bindings.values()) {
            Path root = binding.localRoot();
            if (root != null && (root.startsWith(workspace) || workspace.startsWith(root))) {
                throw configuration("workspace overlaps local location " + binding.location().id());
            }
        }
    }

    private void addLocal(String id, Path rootDir) {
        Path root = canonicalPath(rootDir);
        for (Binding binding : bindings.values()) {
            Path other = binding.localRoot();
            if (other != null && (root.startsWith(other) || other.startsWith(root))) {
                throw configuration("overlapping local locations " + binding.location().id() + " and " + id);
            }
        }
        bindings.put(id, new Binding(new Location(id, Role.ASSET, "local", null), "local:" + root, root, new LocalStorageBackend(root)));
    }

    private void addS3(String id, Role role, FileServiceConfig.S3 config, String bucket, boolean autoCreate) {
        String endpoint = required(config.endpoint(), "endpoint for " + id);
        URI uri = URI.create(endpoint);
        if (uri.getHost() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw configuration("invalid S3 endpoint for " + id);
        }
        int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        String namespace = uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port + "/" + bucket;
        for (Binding binding : bindings.values()) {
            if (Objects.equals(namespace, binding.namespace())) {
                throw configuration("shared S3 namespace for locations " + binding.location().id() + " and " + id);
            }
        }
        var backend = new S3StorageBackend(endpoint, required(config.accessKey(), "access-key for " + id),
                required(config.secretKey(), "secret-key for " + id), bucket, autoCreate);
        bindings.put(id, new Binding(new Location(id, role, "s3", bucket), namespace, null, backend));
    }

    private static Path canonicalPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute));
        } catch (IOException e) {
            throw new IllegalStateException("failed to resolve storage directory " + path, e);
        }
    }

    private static <T> T required(Optional<T> value, String setting) {
        return value.filter(v -> !(v instanceof String string) || !string.isBlank())
                .orElseThrow(() -> configuration("missing " + setting));
    }

    private static IllegalStateException configuration(String message) {
        return new IllegalStateException("invalid storage configuration: " + message);
    }
}
