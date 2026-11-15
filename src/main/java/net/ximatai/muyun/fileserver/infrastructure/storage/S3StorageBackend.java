package net.ximatai.muyun.fileserver.infrastructure.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import net.ximatai.muyun.fileserver.application.storage.ReceptionStorage.ObjectInfo;
import net.ximatai.muyun.fileserver.common.exception.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

final class S3StorageBackend implements ObjectStorageBackend {
    private final String bucket;
    private final MinioClient client;
    private final boolean autoCreateBucket;

    S3StorageBackend(String endpoint, String accessKey, String secretKey, String bucket, boolean autoCreateBucket) {
        this.bucket = bucket;
        this.autoCreateBucket = autoCreateBucket;
        client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        client.setTimeout(5000, 30000, 30000);
    }

    @Override
    public void initialize() {
        if (autoCreateBucket) {
            try {
                if (!bucketExists()) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
            } catch (Exception e) {
                throw new StorageException("failed to initialize S3 bucket", e);
            }
        }
    }

    @Override
    public void verifyReadiness() {
        try {
            if (!bucketExists()) {
                throw new StorageException("S3 bucket is not accessible");
            }
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("failed to verify S3 readiness", e);
        }
    }

    List<ObjectInfo> list(String prefix, int limit) throws Exception {
        var result = new ArrayList<ObjectInfo>();
        var args = ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build();
        for (var value : client.listObjects(args)) {
            var item = value.get();
            result.add(new ObjectInfo(item.objectName().substring(prefix.length()), item.size(), item.etag()));
            if (result.size() == limit) {
                break;
            }
        }
        return result;
    }

    void upload(String key, Path path) throws Exception {
        client.uploadObject(UploadObjectArgs.builder().bucket(bucket).object(key).filename(path.toString()).build());
    }

    @Override
    public void write(Path tempFile, String storageKey) {
        try (InputStream inputStream = Files.newInputStream(tempFile)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .stream(inputStream, Files.size(tempFile), -1)
                    .build());
        } catch (Exception exception) {
            throw new StorageException("failed to move file to permanent storage", exception);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .build());
        } catch (Exception exception) {
            throw new StorageException("failed to open stored file", exception);
        }
    }

    @Override
    public InputStream openRange(String storageKey, long start, long length) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .offset(start)
                    .length(length)
                    .build());
        } catch (Exception exception) {
            throw new StorageException("failed to open stored file range", exception);
        }
    }

    @Override
    public void deleteIfExists(String storageKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .build());
        } catch (ErrorResponseException exception) {
            if (isMissingObject(exception)) {
                return;
            }
            throw new StorageException("failed to delete stored file", exception);
        } catch (Exception exception) {
            throw new StorageException("failed to delete stored file", exception);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .build());
            return true;
        } catch (ErrorResponseException exception) {
            if (isMissingObject(exception)) {
                return false;
            }
            throw new StorageException("failed to check stored file existence", exception);
        } catch (Exception exception) {
            throw new StorageException("failed to check stored file existence", exception);
        }
    }

    private boolean bucketExists() throws ErrorResponseException, MinioException, IOException, NoSuchAlgorithmException, InvalidKeyException {
        return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
    }

    private boolean isMissingObject(ErrorResponseException exception) {
        String code = exception.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code);
    }
}
