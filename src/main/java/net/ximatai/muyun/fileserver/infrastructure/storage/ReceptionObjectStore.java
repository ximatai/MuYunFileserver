package net.ximatai.muyun.fileserver.infrastructure.storage;

import io.minio.ListObjectsArgs;
import io.minio.UploadObjectArgs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.application.storage.ReceptionStorage;
import net.ximatai.muyun.fileserver.domain.file.StoredObject;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
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
        var location = locations.require(locationId, StorageLocations.Role.RECEPTION);
        var result = new ArrayList<ObjectInfo>();
        var args = ListObjectsArgs.builder().bucket(location.bucket()).prefix(prefix).recursive(true).build();
        for (var value : backend(locationId).client().listObjects(args)) {
            var item = value.get();
            result.add(new ObjectInfo(item.objectName().substring(prefix.length()), item.size(), item.etag()));
            if (result.size() == limit) {
                break;
            }
        }
        return result;
    }

    @Override
    public InputStream open(StoredObject object) {
        return backend(object.locationId()).open(object.key());
    }

    @Override
    public void upload(StoredObject object, Path path) throws Exception {
        var location = locations.require(object.locationId(), StorageLocations.Role.RECEPTION);
        backend(object.locationId()).client().uploadObject(UploadObjectArgs.builder()
                .bucket(location.bucket()).object(object.key()).filename(path.toString()).build());
    }

    @Override
    public void delete(StoredObject object) {
        backend(object.locationId()).deleteIfExists(object.key());
    }

    private S3StorageBackend backend(String id) {
        return (S3StorageBackend) locations.backend(id, StorageLocations.Role.RECEPTION);
    }
}
