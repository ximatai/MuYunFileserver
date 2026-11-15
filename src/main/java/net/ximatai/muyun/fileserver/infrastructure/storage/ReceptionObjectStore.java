package net.ximatai.muyun.fileserver.infrastructure.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.application.storage.ReceptionStorage;
import net.ximatai.muyun.fileserver.domain.file.StoredObject;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

@ApplicationScoped
public class ReceptionObjectStore implements ReceptionStorage {
    @Inject StorageLocations locations;

    @Override
    public Target target() {
        var location = locations.defaultReception();
        return new Target(location.id(), location.bucket());
    }

    @Override
    public List<ObjectInfo> list(String locationId, String prefix, int limit) throws Exception {
        return backend(locationId).list(prefix, limit);
    }

    @Override
    public InputStream open(StoredObject object) {
        return backend(object.locationId()).open(object.key());
    }

    @Override
    public void upload(StoredObject object, Path path) throws Exception {
        backend(object.locationId()).upload(object.key(), path);
    }

    @Override
    public void delete(StoredObject object) {
        backend(object.locationId()).deleteIfExists(object.key());
    }

    private S3StorageBackend backend(String id) {
        return (S3StorageBackend) locations.backend(id, StorageLocations.Role.RECEPTION);
    }
}
