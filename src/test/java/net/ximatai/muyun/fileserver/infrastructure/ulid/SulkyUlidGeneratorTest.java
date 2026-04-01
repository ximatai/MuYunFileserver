package net.ximatai.muyun.fileserver.infrastructure.ulid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SulkyUlidGeneratorTest {

    @Test
    void shouldGenerateValidUlid() {
        SulkyUlidGenerator generator = new SulkyUlidGenerator();

        assertTrue(generator.isValid(generator.nextUlid()));
    }

    @Test
    void shouldRejectInvalidUlid() {
        SulkyUlidGenerator generator = new SulkyUlidGenerator();

        assertFalse(generator.isValid("not-a-ulid"));
    }
}
