package com.afella.customautostorage;

import java.util.Locale;

public final class AutoStorageIds {
    static final String AUTO_STORAGE = "Furniture_AutoStorage";
    private static final String AUTO_STORAGE_LOWER;

    private AutoStorageIds() {
    }

    public static boolean isAutoStorageId(String id) {
        if (id != null && !id.isEmpty()) {
            String normalized = normalize(id);
            if (normalized.isEmpty()) {
                return false;
            } else {
                String lower = normalized.toLowerCase(Locale.ROOT);
                if (AUTO_STORAGE_LOWER.equals(lower)) {
                    return true;
                } else {
                    return lower.startsWith(AUTO_STORAGE_LOWER) || containsBoundedToken(lower, AUTO_STORAGE_LOWER);
                }
            }
        } else {
            return false;
        }
    }

    private static String normalize(String id) {
        String normalized = id.trim();
        if (normalized.isEmpty()) {
            return "";
        } else {
            if (normalized.charAt(0) == '*') {
                normalized = normalized.substring(1);
            }

            return normalized;
        }
    }

    private static boolean containsBoundedToken(String value, String token) {
        if (value != null && !value.isEmpty() && token != null && !token.isEmpty()) {
            int fromIndex = 0;

            while(true) {
                int index = value.indexOf(token, fromIndex);
                if (index < 0) {
                    return false;
                }

                int beforeIndex = index - 1;
                int afterIndex = index + token.length();
                boolean boundedBefore = beforeIndex < 0 || !Character.isLetterOrDigit(value.charAt(beforeIndex));
                boolean boundedAfter = afterIndex >= value.length() || !Character.isLetterOrDigit(value.charAt(afterIndex));
                if (boundedBefore && boundedAfter) {
                    return true;
                }

                fromIndex = index + 1;
            }
        } else {
            return false;
        }
    }

    static {
        AUTO_STORAGE_LOWER = AUTO_STORAGE.toLowerCase(Locale.ROOT);
    }
}