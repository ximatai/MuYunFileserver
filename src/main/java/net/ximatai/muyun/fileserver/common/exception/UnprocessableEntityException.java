package net.ximatai.muyun.fileserver.common.exception;

public class UnprocessableEntityException extends ClientException {

    public UnprocessableEntityException(String message) {
        super(422, message);
    }
}
