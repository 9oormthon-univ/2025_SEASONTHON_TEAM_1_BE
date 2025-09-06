package com.goormthonuniv.cleannews.verify;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.function.Consumer;

/**
 * 검증 대상 텍스트로부터 Google CSE에投할 검색 쿼리들을 생성한다.
 *
 * 설계 포인트
 * - 한국어/영어 키워드 모두 지원 (불용어 제거 + 단순 토크나이즈)
 * - 이벤트/브랜드/핸들/장소/날짜 등 ExtractedFacts를 최우선 앵커로 사용
 * - 예매/공지/라인업/티켓 등 도메인 특화 템플릿(ko/en) 지원
 * - site: 필터는 "티켓/공지/예매/콘서트/공연"류 신호가 있을 때만 보수적으로 추가
 * - duplicate 제거 및 길이 제한(<= 120자)로 API 안전성 확보
 * - 출력은 원문 문자열(엔코딩 X). URL 조립 시점에서 반드시 URL-encode 하세요.
 */
public final class SearchQueryBuilder {

    private static final int MAX_QUERY_LEN = 120;

    private static final Pattern SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}#@._]+");
    private static final Pattern EMOJI = Pattern.compile("[^\\p{Print}\\p{IsAlphabetic}\\p{IsDigit}\\s#@._\\-’'\"()\\[\\]]");

    /** 한국어 불용어(축약) */
    private static final Set<String> STOP_KO = Set.of(
            "그리고","그러나","하지만","또는","및","또","등","은","는","이","가","을","를","에","의",
            "도","만","로","으로","에서","에게","했다","합니다","합니다.","합니다","합니다,",
            "오늘","이번","해당","관련","제","및","좀","더","수","있는","없는","입니다","입니다.","으로",
            "대한","때문","중","동안","예정","가능","합니다","공지","안내"
    );

    /** 영어 불용어(축약) */
    private static final Set<String> STOP_EN = Set.of(
            "the","a","an","and","or","but","to","of","in","on","for","with","at","by","as","is","are",
            "this","that","these","those","be","been","was","were","it","its","from","about","we","you",
            "they","i","he","she","them","our","your","their","will","can","may","more","most","over"
    );

    /** 예매/공연 관련 키워드 */
    private static final List<String> TICKET_KO = List.of("예매","티켓","티켓오픈","공지","안내","라인업","공식","콘서트","공연","일정","좌석","가격");
    private static final List<String> TICKET_EN = List.of("ticket","tickets","ticketing","on sale","lineup","official","concert","show","notice","announcement","schedule","venue","seating","price","booking");

    private SearchQueryBuilder() {}

    /** 메인 엔트리 */
    static List<String> buildQueries(String normTitle, String normBody, ExtractedFacts facts) {
        // 1) 텍스트 정규화 & 토큰
        String title = sanitize(normTitle);
        String body  = sanitize(normBody);

        // 2) 핵심 앵커 추출
        var anchors = collectAnchors(facts);

        // 3) 일반 키워드 상위 N
        var generic = topKeywords(title + " " + body, 10);

        // 4) 템플릿 쿼리 생성
        var queries = new LinkedHashSet<String>();

        // 4-1 이벤트명 중심
        ifPresent(facts != null ? facts.getEventName() : null, ev -> {
            queries.add(quote(ev)); // 정확어구
            for (String kw : TICKET_KO) queries.add(quote(ev) + " " + kw);
            for (String kw : TICKET_EN) queries.add(quote(ev) + " " + kw);
        });

        // 4-2 브랜드/핸들 + 이벤트
        if (!anchors.isEmpty()) {
            ifPresent(facts != null ? facts.getEventName() : null, ev -> {
                for (String a : anchors) {
                    queries.add(a + " " + quote(ev));
                    queries.add(quote(ev) + " " + a);
                }
            });
        }

        // 4-3 장소/도시/날짜 조합
        String place = facts != null ? coalesce(facts.getLocationVenue(), facts.getLocationCity()) : null;
        String date  = facts != null ? facts.getDateText() : null;
        ifPresent(facts != null ? facts.getEventName() : null, ev -> {
            if (notBlank(place)) {
                queries.add(quote(ev) + " " + place);
                queries.add(quote(ev) + " " + place + " 일정");
                queries.add(quote(ev) + " " + place + " schedule");
            }
            if (notBlank(date)) {
                queries.add(quote(ev) + " " + date);
                queries.add(quote(ev) + " " + date + " 예매");
            }
        });

        // 4-4 해시태그/핸들 단독
        for (String h : safeList(facts != null ? facts.getHashtags() : null)) {
            if (h.startsWith("#")) queries.add(h + " 콘서트");
        }
        for (String h : safeList(facts != null ? facts.getOrgHandles() : null)) {
            if (h.startsWith("@")) {
                String bare = h.substring(1);
                queries.add(bare + " 공식 공지");
                queries.add("site:instagram.com " + bare);
            }
        }

        // 4-5 공연성 문맥이면 예매처/공지 site 필터(보수적)
        boolean seemsTicketing = containsAny(title + " " + body,
                List.of("예매","티켓","티켓오픈","공연","콘서트","라인업","NOL","인터파크","멜론티켓","예스24"));
        if (seemsTicketing || (facts != null && notBlank(facts.getEventName()))) {
            queries.add(appendSite(quoteOr(facts != null ? facts.getEventName() : null, "콘서트"), "tickets.interpark.com"));
            queries.add(appendSite(quoteOr(facts != null ? facts.getEventName() : null, "콘서트"), "ticket.interpark.com"));
            queries.add(appendSite(quoteOr(facts != null ? facts.getEventName() : null, "콘서트"), "interpark.com"));
            queries.add(appendSite(quoteOr(facts != null ? facts.getEventName() : null, "concert"), "interpark.com"));
            queries.add(appendSite(quoteOr(facts != null ? facts.getEventName() : null, "concert"), "naver.com"));
        }

        // 4-6 일반 키워드 조합(짧게)
        ifPresent(facts != null ? facts.getEventName() : null, ev -> {
            var shortGen = generic.stream().limit(5).collect(Collectors.toList());
            if (!shortGen.isEmpty()) queries.add(quote(ev) + " " + String.join(" ", shortGen));
        });

        // 4-7 이벤트명 없으면 앵커+티켓 키워드 조합
        if (facts == null || isBlank(facts.getEventName())) {
            for (String a : anchors) {
                for (String k : List.of("예매","티켓","공지","라인업","concert","ticket")) {
                    queries.add(a + " " + k);
                }
            }
        }

        // 5) 후처리
        var cleaned = queries.stream()
                .map(SearchQueryBuilder::normalizeSpaces)
                .map(SearchQueryBuilder::stripWeirdQuotes)
                .map(q -> q.length() > MAX_QUERY_LEN ? q.substring(0, MAX_QUERY_LEN) : q)
                .filter(q -> q.chars().anyMatch(Character::isLetterOrDigit))
                .limit(24)
                .collect(Collectors.toList());

        // 6) 비상시(완전 공백) 최소 쿼리 보장
        if (cleaned.isEmpty()) {
            cleaned.addAll(fallbackQueries(title, body, facts));
        }

        return cleaned;
    }

    // ----------------------------- helpers -----------------------------

    private static void ifPresent(String value, Consumer<String> consumer) {
        if (value != null && !value.isBlank()) consumer.accept(value);
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        n = EMOJI.matcher(n).replaceAll(" ");
        n = n.replaceAll("[“”]", "\"").replaceAll("[‘’]", "'");
        return normalizeSpaces(n);
    }

    private static String normalizeSpaces(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static String stripWeirdQuotes(String s) {
        return s.replace("''", "'").replace("\"\"", "\"");
    }

    private static Set<String> collectAnchors(ExtractedFacts facts) {
        var set = new LinkedHashSet<String>();
        if (facts == null) return set;
        safeList(facts.getBrandNames()).forEach(set::add);
        safeList(facts.getOrgHandles()).forEach(h -> set.add(h.startsWith("@") ? h.substring(1) : h));
        safeList(facts.getHashtags()).forEach(ht -> { if (ht.startsWith("#")) set.add(ht); });
        if (notBlank(facts.getLocationVenue())) set.add(facts.getLocationVenue());
        if (notBlank(facts.getLocationCity())) set.add(facts.getLocationCity());
        return set;
    }

    private static List<String> topKeywords(String text, int limit) {
        if (isBlank(text)) return List.of();
        String[] toks = SPLIT.split(text.toLowerCase(Locale.ROOT));
        Map<String, Integer> freq = new HashMap<>();
        for (String t : toks) {
            if (isBlank(t)) continue;
            String token = t.trim();
            if (token.length() <= 1) continue;
            if (STOP_KO.contains(token) || STOP_EN.contains(token)) continue;
            if (!token.startsWith("#") && !token.startsWith("@")) {
                if (token.chars().noneMatch(Character::isLetter) && token.chars().anyMatch(Character::isDigit)) continue;
            }
            freq.merge(token, 1, Integer::sum);
        }
        return freq.entrySet().stream()
                .sorted((a,b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private static boolean containsAny(String text, List<String> needles) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String n : needles) if (t.contains(n.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String appendSite(String q, String site) {
        if (isBlank(q)) return "site:" + site;
        return q + " site:" + site;
    }

    private static String quote(String s) {
        if (isBlank(s)) return "";
        String val = s.replace("\"", " ");
        return "\"" + val.trim() + "\"";
    }

    private static String quoteOr(String prefer, String fallback) {
        return notBlank(prefer) ? quote(prefer) : fallback;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String coalesce(String a, String b) { return notBlank(a) ? a : (notBlank(b) ? b : null); }

    private static <T> List<T> safeList(List<T> l) { return l == null ? List.of() : l; }

    private static List<String> fallbackQueries(String title, String body, ExtractedFacts facts) {
        var list = new ArrayList<String>();
        String e = (facts != null) ? facts.getEventName() : null;
        if (notBlank(e)) {
            list.add(quote(e));
            list.add(quote(e) + " 예매");
            list.add(quote(e) + " ticket");
        } else {
            var kws = topKeywords(title + " " + body, 5);
            if (!kws.isEmpty()) list.add(String.join(" ", kws));
        }
        return list;
    }
}