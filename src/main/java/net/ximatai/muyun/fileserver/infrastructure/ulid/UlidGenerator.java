package net.ximatai.muyun.fileserver.infrastructure.ulid;

public interface UlidGenerator {

    String nextUlid();

    boolean isValid(String value);
}
