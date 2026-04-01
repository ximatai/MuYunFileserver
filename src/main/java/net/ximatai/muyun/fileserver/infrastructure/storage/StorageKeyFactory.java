package net.ximatai.muyun.fileserver.infrastructure.storage;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

@ApplicationScoped
public class StorageKeyFactory {

    private final Clock clock;

    public StorageKeyFactory() {
        this(Clock.systemUTC());
    }

    StorageKeyFactory(Clock clock) {
        this.clock = clock;
    }

    public String build(String tenantId, String fileId) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return "tenant/%s/%04d/%02d/%02d/%s".formatted(
                tenantId,
                today.getYear(),
                today.getMonthValue(),
                today.getDayOfMonth(),
                fileId
        );
    }
}
