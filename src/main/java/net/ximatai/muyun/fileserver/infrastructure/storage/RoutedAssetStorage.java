package net.ximatai.muyun.fileserver.infrastructure.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.application.storage.AssetStorage;
import net.ximatai.muyun.fileserver.domain.file.StoredObject;

import java.io.InputStream;
import java.nio.file.Path;

@ApplicationScoped
public class RoutedAssetStorage implements AssetStorage {
    @Inject StorageLocations locations;

    @Override
    public Location writeLocation() {
        var location = locations.defaultAssets();
        return new Location(location.id(), location.type(), location.bucket());
    }

    @Override
    public void write(Path staged, StoredObject object) {
        backend(object).write(staged, object.key());
    }

    @Override
    public InputStream open(StoredObject object) {
        return backend(object).open(object.key());
    }

    @Override
    public InputStream openRange(StoredObject object, long start, long length) {
        return backend(object).openRange(object.key(), start, length);
    }

    @Override
    public boolean exists(StoredObject object) {
        return backend(object).exists(object.key());
    }

    @Override
    public void deleteIfExists(StoredObject object) {
        backend(object).deleteIfExists(object.key());
    }

    private ObjectStorageBackend backend(StoredObject object) {
        return locations.backend(object.locationId(), StorageLocations.Role.ASSET);
    }
}
