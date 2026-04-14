package net.ximatai.muyun.fileserver.common.exception;

public class UnprocessableContentException extends AppException {

    public UnprocessableContentException(String message) {
        super(422, message);
    }
}
