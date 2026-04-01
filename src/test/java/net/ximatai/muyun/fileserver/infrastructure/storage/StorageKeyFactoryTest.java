package net.ximatai.muyun.fileserver.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageKeyFactoryTest {

    @Test
    void shouldBuildStorageKeyWithTenantAndDatePartition() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-01T10:00:00Z"), ZoneOffset.UTC);
        StorageKeyFactory factory = new StorageKeyFactory(fixedClock);

        String storageKey = factory.build("tenant-a", "01JABCDEF1234567890ABCDEF");

        assertEquals("tenant/tenant-a/2026/04/01/01JABCDEF1234567890ABCDEF", storageKey);
    }
}
