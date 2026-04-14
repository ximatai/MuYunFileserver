package net.ximatai.muyun.fileserver.application;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ViewerType {
    PDF("pdf"),
    IMAGE("image"),
    TEXT("text"),
    FALLBACK("fallback");

    private final String value;

    ViewerType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
