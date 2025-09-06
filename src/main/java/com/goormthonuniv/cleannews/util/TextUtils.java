package com.goormthonuniv.cleannews.util;

import java.util.*;
import java.util.regex.Pattern;

public final class TextUtils {
    private static final Pattern URL = Pattern.compile("(https?://\\S+)");
    private static final Pattern EMOJI = Pattern.compile("[\\p{So}\\p{Cn}]+"); // 대충 이모지/기타 통제
    private static final int MAX_LEN = 1200; // 본문 1.2k chars 트렁케이트

    private TextUtils() {}

    public static String normalize(String text) {
        if (text == null) return "";
        String t = text.strip();
        t = URL.matcher(t).replaceAll(" ");
        t = EMOJI.matcher(t).replaceAll(" ");
        t = t.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (t.length() > MAX_LEN) {
            t = t.substring(0, MAX_LEN);
        }
        return t;
    }

    public static Set<String> stopwords() {
        return Set.of(
                // 한/영 혼합 간단 스톱워드
                "은","는","이","가","을","를","에","에서","으로","로","와","과","도","만","의","하다",
                "the","a","an","to","of","and","or","is","are","in","on","for","with","by","at","as","that"
        );
    }
}