package net.ximatai.muyun.fileserver.common.exception;

public class AppException extends RuntimeException {

    private final int status;

    public AppException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
