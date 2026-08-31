package com.alz.assistant;

/** Stateful filter for <think> blocks split across streaming chunks. */
public final class StreamingThinkFilter {
    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";
    private final StringBuilder pending = new StringBuilder();
    private boolean insideThink;

    public String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        pending.append(chunk);
        StringBuilder visible = new StringBuilder();
        while (pending.length() > 0) {
            String lower = pending.toString().toLowerCase();
            if (insideThink) {
                int close = lower.indexOf(CLOSE);
                if (close < 0) {
                    retainPossiblePrefix(CLOSE);
                    break;
                }
                pending.delete(0, close + CLOSE.length());
                insideThink = false;
                continue;
            }
            int open = lower.indexOf(OPEN);
            if (open >= 0) {
                visible.append(pending, 0, open);
                pending.delete(0, open + OPEN.length());
                insideThink = true;
                continue;
            }
            int held = suffixPrefixLength(lower, OPEN);
            int emitLength = pending.length() - held;
            if (emitLength > 0) {
                visible.append(pending, 0, emitLength);
                pending.delete(0, emitLength);
            }
            break;
        }
        return visible.toString();
    }

    public String finish() {
        if (insideThink) {
            pending.setLength(0);
            return "";
        }
        String value = ThinkContentFilter.strip(pending.toString());
        pending.setLength(0);
        return value;
    }

    private void retainPossiblePrefix(String marker) {
        String lower = pending.toString().toLowerCase();
        int held = suffixPrefixLength(lower, marker);
        if (held == 0) pending.setLength(0);
        else pending.delete(0, pending.length() - held);
    }

    private static int suffixPrefixLength(String value, String marker) {
        int max = Math.min(value.length(), marker.length() - 1);
        for (int length = max; length > 0; length--) {
            if (value.regionMatches(true, value.length() - length, marker, 0, length)) return length;
        }
        return 0;
    }
}
