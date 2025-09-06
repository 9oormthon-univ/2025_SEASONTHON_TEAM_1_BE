package com.goormthonuniv.cleannews.service;

import com.goormthonuniv.cleannews.util.TextUtils;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 특정 사건/브랜드 하드코딩 없이 일반화.
 * - @handles, #hashtags, 따옴표/괄호 내 구절, URL 호스트 토큰, 한/영 토큰을 종합하여 쿼리 구성
 * - 레벤슈타인 병합으로 유사 토큰 정리
 * - "이벤트/공지/모집/무료/당첨/체험단/공식" 같은 '행위/안내' 트리거만 일반 단어로 사용(브랜드 비의존)
 */
@Service
public class KeywordService {

    private static final Pattern HANDLE = Pattern.compile("@[A-Za-z0-9_\\.]+");
    private static final Pattern HASHTAG = Pattern.compile("#[A-Za-z0-9_가-힣]+");
    private static final Pattern QUOTED = Pattern.compile("‘([^’]+)’|\"([^\"]+)\"|\\(([^)]+)\\)");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9가-힣]{2,}"); // 간단 토큰
    private static final Set<String> TRIGGERS = Set.of("이벤트","프로모션","공지","공식","모집","무료","당첨","체험단");

    /** 원문(title+text)에서 엔티티성 키워드 추출 */
    public List<String> extractEntities(String raw, String sourceUrl) {
        if (raw == null) raw = "";
        LinkedHashSet<String> key = new LinkedHashSet<>();

        // @handles / #hashtags
        Matcher m = HANDLE.matcher(raw);
        while (m.find()) key.add(m.group().substring(1)); // @제거
        m = HASHTAG.matcher(raw);
        while (m.find()) key.add(m.group().substring(1)); // #제거

        // 따옴표/괄호 안 구절(상품/좌석명 등)
        m = QUOTED.matcher(raw);
        while (m.find()) {
            for (int i=1;i<=m.groupCount();i++) {
                String g = m.group(i);
                if (g != null && g.strip().length() >= 2) key.add(g.strip());
            }
        }

        // URL 호스트 토큰 (ex: instagram.com -> instagram / _tripgoing -> tripgoing)
        for (String t : hostTokens(sourceUrl)) key.add(t);

        // 일반 트리거(행위/안내; 브랜드 아님)
        for (String t : TRIGGERS) if (raw.contains(t)) key.add(t);

        return key.stream().map(this::normalizeToken).filter(s -> s.length() >= 2).toList();
    }

    /** 정규화된 텍스트에서 상위 토큰 추출 (스톱워드 제외 + 근접 병합) */
    public List<String> topKeywords(String normalized, int limit) {
        if (normalized == null || normalized.isBlank()) return List.of();
        var stop = TextUtils.stopwords();
        Map<String, Integer> tf = new HashMap<>();
        Matcher tok = TOKEN.matcher(normalized);
        while (tok.find()) {
            String t = tok.group().toLowerCase(Locale.ROOT);
            if (stop.contains(t) || t.length() < 2) continue;
            tf.merge(t, 1, Integer::sum);
        }
        // 근접 병합(편집거리 1)
        var keys = new ArrayList<>(tf.keySet());
        var merged = new LinkedHashMap<String, Integer>();
        var dist = new LevenshteinDistance(1);
        for (String k : keys) {
            String rep = merged.keySet().stream()
                    .filter(x -> dist.apply(x, k) <= 1)
                    .findFirst().orElse(k);
            merged.merge(rep, tf.get(k), Integer::sum);
        }
        return merged.entrySet().stream()
                .sorted((a,b)->Integer.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /** 최종 쿼리용 키워드: 엔티티 우선 + 일반 토큰 보강 (브랜드 하드코딩 없이 동작) */
    public List<String> boostedKeywords(String title, String rawText, String sourceUrl, int limit) {
        String mix = ((title==null?"":title) + " " + (rawText==null?"":rawText));
        // 1) 엔티티성 키워드 (handles/hashtags/quoted/host/trigger)
        LinkedHashSet<String> set = new LinkedHashSet<>(extractEntities(mix, sourceUrl));
        // 2) 정규화 본문 기반 일반 토큰
        var norm = TextUtils.normalize(mix);
        set.addAll(topKeywords(norm, Math.max(limit, 12)));
        // 과잉 토큰 제거(숫자만 등)
        var out = set.stream()
                .map(this::normalizeToken)
                .filter(s -> s.length() >= 2 && !s.matches("\\d+"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 상위 limit로 제한
        return new ArrayList<>(out).subList(0, Math.min(out.size(), limit));
    }

    public String buildQuery(List<String> keywords) {
        if (keywords.isEmpty()) return "";
        return String.join(" ", keywords);
    }

    // ===== helpers =====

    private String normalizeToken(String t) {
        if (t == null) return "";
        return t.replaceAll("[^A-Za-z0-9가-힣]", "").toLowerCase(Locale.ROOT);
    }

    private List<String> hostTokens(String url) {
        if (url == null || url.isBlank()) return List.of();
        try {
            String host = URI.create(url).getHost();
            if (host == null) return List.of();
            // instagram.com -> instagram, com (com은 제거)
            String[] parts = host.split("\\.");
            LinkedHashSet<String> tokens = new LinkedHashSet<>();
            for (String p : parts) {
                if (p.equalsIgnoreCase("com") || p.equalsIgnoreCase("co") || p.equalsIgnoreCase("kr")) continue;
                if (p.length() >= 2) tokens.add(p.toLowerCase(Locale.ROOT));
            }
            return new ArrayList<>(tokens);
        } catch (Exception e) {
            return List.of();
        }
    }
}