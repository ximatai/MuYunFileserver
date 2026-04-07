package net.ximatai.muyun.fileserver.infrastructure.storage;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.YearMonth;
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
        YearMonth currentMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        return "%s/%04d/%02d/%s".formatted(
                tenantId,
                currentMonth.getYear(),
                currentMonth.getMonthValue(),
                fileId
        );
    }
}
