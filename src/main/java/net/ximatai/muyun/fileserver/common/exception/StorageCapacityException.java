package net.ximatai.muyun.fileserver.common.exception;

public class StorageCapacityException extends ServerException {

    public StorageCapacityException(String message) {
        super(507, message);
    }
}
