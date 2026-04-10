package net.ximatai.muyun.fileserver.common.exception;

public class UnprocessableEntityException extends AppException {

    public UnprocessableEntityException(String message) {
        super(422, message);
    }
}
