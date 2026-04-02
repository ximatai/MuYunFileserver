package net.ximatai.muyun.fileserver.common.log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class OperationLog {

    private OperationLog() {
    }

    public static String format(String operation, String result, String... keyValues) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("operation", operation);
        fields.put("result", result);

        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            fields.put(keyValues[index], sanitize(keyValues[index + 1]));
        }

        StringJoiner joiner = new StringJoiner(" ");
        fields.forEach((key, value) -> joiner.add(key + "=" + sanitize(value)));
        return joiner.toString();
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace(" ", "_");
    }
}
