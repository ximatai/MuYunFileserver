package net.ximatai.muyun.fileserver.common.exception;

/** An expected client-side rejection with a 4xx HTTP response. */
public abstract class ClientException extends AppException {

    protected ClientException(int status, String message) {
        super(status, message);
        if (status < 400 || status >= 500) {
            throw new IllegalArgumentException("ClientException status must be in the 4xx range");
        }
    }
}
