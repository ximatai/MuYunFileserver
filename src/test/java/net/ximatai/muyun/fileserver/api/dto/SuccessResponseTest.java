package net.ximatai.muyun.fileserver.api.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuccessResponseTest {

    @Test
    void shouldDefaultSuccessToTrue() {
        SuccessResponse<String> response = new SuccessResponse<>("ok");

        assertTrue(response.success());
        assertEquals("ok", response.data());
    }
}
