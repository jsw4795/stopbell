package com.stopbell.notification.client;

/** Optional display evidence copied from the original persisted ALARM PASSED Event, never a live location. */
public record PassedNotificationContext(String currentStopName, Integer stopsPastTarget) {
    private static final int MAX_STOP_NAME_CODE_POINTS = 80;

    public PassedNotificationContext {
        currentStopName = displayName(currentStopName);
        stopsPastTarget = stopsPastTarget != null && stopsPastTarget > 0 ? stopsPastTarget : null;
    }

    private static String displayName(String value) {
        if (value == null || value.codePoints().anyMatch(PassedNotificationContext::unprintable)) {
            return null;
        }
        String name = value.strip();
        if (name.codePoints().allMatch(code -> Character.isWhitespace(code) || Character.isSpaceChar(code))) {
            return null;
        }
        if (name.codePointCount(0, name.length()) > MAX_STOP_NAME_CODE_POINTS) {
            return name.substring(0, name.offsetByCodePoints(0, MAX_STOP_NAME_CODE_POINTS - 1)) + "…";
        }
        return name;
    }

    private static boolean unprintable(int code) {
        int type = Character.getType(code);
        return Character.isISOControl(code) || type == Character.FORMAT || type == Character.SURROGATE
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }
}
