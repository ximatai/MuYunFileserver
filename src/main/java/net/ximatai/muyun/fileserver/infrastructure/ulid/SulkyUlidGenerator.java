package net.ximatai.muyun.fileserver.infrastructure.ulid;

import de.huxhorn.sulky.ulid.ULID;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SulkyUlidGenerator implements UlidGenerator {

    private final ULID ulid = new ULID();

    @Override
    public String nextUlid() {
        return ulid.nextULID();
    }

    @Override
    public boolean isValid(String value) {
        try {
            ULID.parseULID(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
