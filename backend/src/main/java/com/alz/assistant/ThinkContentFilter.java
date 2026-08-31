package com.alz.assistant;

import java.util.regex.Pattern;

/** Removes model reasoning blocks before content is displayed or persisted. */
public final class ThinkContentFilter {

    private static final Pattern COMPLETE_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern LEADING_REASONING = Pattern.compile("(?is)^.*?</think>");
    private static final Pattern TAG = Pattern.compile("(?is)</?think>");

    private ThinkContentFilter() {
    }

    public static String strip(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String result = COMPLETE_BLOCK.matcher(value).replaceAll("");
        if (result.toLowerCase().contains("</think>")) {
            result = LEADING_REASONING.matcher(result).replaceFirst("");
        }
        return TAG.matcher(result).replaceAll("").trim();
    }
}
