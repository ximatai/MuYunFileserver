package net.ximatai.muyun.fileserver.common.exception;

/** A controlled service or infrastructure failure with a 5xx HTTP response. */
public abstract class ServerException extends AppException {

    protected ServerException(int status, String message) {
        super(status, message);
        if (status < 500 || status >= 600) {
            throw new IllegalArgumentException("ServerException status must be in the 5xx range");
        }
    }
}
