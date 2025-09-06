package com.goormthonuniv.cleannews.verify;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한국어 텍스트 정규화, 키워드 추출, 날짜/장소 팩트 추출 유틸
 */
class TextKoreanUtils {

    private static final Pattern PUNCT = Pattern.compile("[^\\p{IsHangul}\\p{Alnum}\\s:/.-]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    // 날짜 패턴 (YYYY.MM.DD / YYYY-MM-DD / MM월 DD일 / MM/DD)
    static final Pattern DATE_KR = Pattern.compile(
            "(?:(20\\d{2})[.\\-\\s/]?\\s*(1[0-2]|0?[1-9])[.\\-\\s/]?\\s*(3[01]|[12]?\\d))" + // YYYY.MM.DD
                    "|(?:(1[0-2]|0?[1-9])\\s*월\\s*(3[01]|[12]?\\d)\\s*일)" +                        // MM월 DD일
                    "|(?:(1[0-2]|0?[1-9])[\\-/](3[01]|[12]?\\d))"                                   // MM/DD
    );

    // 주요 공연장/체육관 힌트
    static final String[] VENUE_HINTS = {
            "잠실실내체육관","잠실 체육관","잠실실내","체육관","올림픽공원","KSPO DOME","고척돔","고척 스카이돔",
            "사직실내체육관","수원실내체육관","대구실내체육관","핸드볼경기장","올림픽홀","경기장","아레나","돔","센터"
    };

    /** 텍스트 정규화 */
    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        n = PUNCT.matcher(n).replaceAll(" ");
        n = MULTI_SPACE.matcher(n).replaceAll(" ").trim();
        return n;
    }

    /** 키 토큰 추출 */
    static Set<String> keyTokens(String s) {
        if (s == null || s.isBlank()) return Set.of();
        String[] parts = s.split("\\s+");
        Set<String> out = new LinkedHashSet<>();
        for (String p : parts) {
            if (p.length() < 2) continue;
            if (STOP.contains(p)) continue; // 불용어 제거
            out.add(p);
        }
        return out;
    }

    /** 길이 제한 문자열 */
    static String ellipsize(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static final Set<String> STOP = Set.of(
            "www","com","net","kr",
            "은","는","이","가","을","를","에","의","로","으로","과","와","및","에서","으로부터",
            "the","a","of","to","in","on","for","and","or","by",
            "instagram","tiktok","facebook","youtube"
    );

    /** 날짜/장소 팩트 파싱 */
    static ExtractedFacts parseFacts(String text) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        Matcher m = DATE_KR.matcher(text);
        LocalDate nowYear = LocalDate.now();
        while (m.find()) {
            try {
                if (m.group(1) != null) { // YYYY.MM.DD
                    int y = Integer.parseInt(m.group(1));
                    int mm = Integer.parseInt(m.group(2));
                    int dd = Integer.parseInt(m.group(3));
                    dates.add(LocalDate.of(y, mm, dd));
                } else if (m.group(4) != null) { // MM월 DD일
                    int mm = Integer.parseInt(m.group(4));
                    int dd = Integer.parseInt(m.group(5));
                    dates.add(LocalDate.of(nowYear.getYear(), mm, dd));
                } else if (m.group(6) != null) { // MM/DD
                    int mm = Integer.parseInt(m.group(6));
                    int dd = Integer.parseInt(m.group(7));
                    dates.add(LocalDate.of(nowYear.getYear(), mm, dd));
                }
            } catch (Exception ignored) {}
        }

        String venue = null;
        for (String hint : VENUE_HINTS) {
            if (text.contains(hint.toLowerCase(Locale.ROOT))) {
                venue = hint;
                break;
            }
        }
        return new ExtractedFacts(dates, venue);
    }
}
