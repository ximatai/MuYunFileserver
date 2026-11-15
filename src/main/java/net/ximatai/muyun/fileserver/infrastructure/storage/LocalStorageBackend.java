package net.ximatai.muyun.fileserver.infrastructure.storage;

import net.ximatai.muyun.fileserver.common.exception.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;

final class LocalStorageBackend implements ObjectStorageBackend {
    private final Path root;

    LocalStorageBackend(Path root) {
        this.root = root;
    }

    @Override
    public void initialize() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new StorageException("failed to initialize local storage", e);
        }
    }

    @Override
    public void verifyReadiness() {
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw new StorageException("storage root is not writable");
        }
    }

    @Override
    public void write(Path tempFile, String storageKey) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new StorageException("failed to move file to permanent storage", exception);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            return Files.newInputStream(resolve(storageKey));
        } catch (IOException exception) {
            throw new StorageException("failed to open stored file", exception);
        }
    }

    @Override
    public InputStream openRange(String storageKey, long start, long length) {
        try {
            SeekableByteChannel channel = Files.newByteChannel(resolve(storageKey), StandardOpenOption.READ);
            channel.position(start);
            InputStream inputStream = Channels.newInputStream(channel);
            return new FilterInputStream(inputStream) {
                private long remaining = length;

                @Override
                public int read() throws IOException {
                    if (remaining <= 0) {
                        return -1;
                    }
                    int value = super.read();
                    if (value != -1) {
                        remaining--;
                    }
                    return value;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining <= 0) {
                        return -1;
                    }
                    int read = super.read(b, off, (int) Math.min(len, remaining));
                    if (read > 0) {
                        remaining -= read;
                    }
                    return read;
                }
            };
        } catch (IOException exception) {
            throw new StorageException("failed to open stored file range", exception);
        }
    }

    @Override
    public void deleteIfExists(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw new StorageException("failed to delete stored file", exception);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.exists(resolve(storageKey));
    }

    private Path resolve(String storageKey) {
        Path object = root.resolve(storageKey).normalize();
        if (object.equals(root) || !object.startsWith(root)) {
            throw new StorageException("invalid local storage key");
        }
        return object;
    }
}
