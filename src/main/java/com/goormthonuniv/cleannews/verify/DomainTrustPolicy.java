package com.goormthonuniv.cleannews.verify;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * 출처(도메인) 신뢰도 선험(prior) 점수를 관리/반환하는 정책 클래스.
 * - 정확 매핑(exact)과 서픽스 매핑(suffix)을 모두 지원
 * - "www.", "m.", "mobile.", "amp." 등 일반적인 서브도메인 프리픽스 제거
 * - URL 문자열 또는 호스트 문자열 모두 입력 가능
 *
 * 점수 범위: 0.0 ~ 1.0
 * - 0.90+: 당사/정부/기관 공식 공지
 * - 0.80~0.89: 신뢰 언론/공식 파트너 채널/공식 예매처
 * - 0.60~0.79: 1차 출처(공식 SNS 등)이나 뉴스만큼 검증적이지는 않음
 * - 0.50: 기본치(정보 없음)
 * - 0.30~0.49: 개인 블로그/커뮤니티 등 검증도 낮음
 */
public class DomainTrustPolicy {

    private static final double DEFAULT_PRIOR = 0.50;

    /** 호스트 정확 매핑 (subdomain 포함 전체 문자열 일치) */
    private final Map<String, Double> exactScores = new HashMap<>();

    /** 호스트 서픽스 매핑 (endsWith) — 예: *.naver.com 전체에 기본치 부여 */
    private final Map<String, Double> suffixScores = new HashMap<>();

    /** 뉴스/미디어 도메인 식별용(가벼운 힌트) */
    private final Set<String> newsSuffixSet = new HashSet<>();

    /** 소셜(1차 출처) 식별용(가벼운 힌트) */
    private final Set<String> socialSuffixSet = new HashSet<>();

    public DomainTrustPolicy() {
        // ===== 공식/정부/기관/기업 =====
        putExact("tickets.interpark.com", 0.88); // 인터파크 티켓 공지/예매
        putSuffix(".interpark.com", 0.80);

        putExact("www.airpremia.com", 0.90);
        putSuffix(".airpremia.com", 0.88);

        // ===== 포털 뉴스(상대적 고신뢰) =====
        putExact("news.naver.com", 0.86);
        putSuffix(".naver.com", 0.70); // 기타 네이버 서비스는 기본 포털 가중치
        newsSuffixSet.add("news.naver.com");

        putExact("news.kakao.com", 0.82);
        putSuffix(".daum.net", 0.80);
        newsSuffixSet.add("news.kakao.com");
        newsSuffixSet.add("media.daum.net");

        // 국내 주요 언론 몇 곳 (예시; 필요 시 확장)
        putSuffix(".joongang.co.kr", 0.82);
        putSuffix(".chosun.com", 0.82);
        putSuffix(".hani.co.kr", 0.82);
        putSuffix(".khan.co.kr", 0.82);
        putSuffix(".yonhapnews.co.kr", 0.85);

        // ===== 소셜(공식 계정 1차 출처 — 뉴스보다 한 단계 낮게) =====
        // 공식 계정이라도 오탈자/해킹 가능성 등으로 prior는 0.65~0.70 수준
        putSuffix(".instagram.com", 0.68);
        putSuffix(".x.com", 0.66);
        putSuffix(".twitter.com", 0.66);
        putSuffix(".facebook.com", 0.66);
        putSuffix(".youtube.com", 0.66);
        putSuffix(".tiktok.com", 0.64);
        socialSuffixSet.addAll(List.of(
                "instagram.com","x.com","twitter.com","facebook.com","youtube.com","tiktok.com"
        ));

        // ===== 저신뢰(개인 블로그/카페 류) — 과도한 패널티는 주지 않고 기본보다 약간 낮게 =====
        putSuffix(".blog.naver.com", 0.45);
        putSuffix(".tistory.com", 0.45);
        putSuffix(".medium.com", 0.48);
        putSuffix(".brunch.co.kr", 0.48);

        // 그 외 자주 보게 될 후보 몇 개
        putSuffix(".google.com", 0.50); // 검색/캐시 링크 등
        putSuffix(".googleusercontent.com", 0.50);
        putSuffix(".notion.site", 0.40);
        putSuffix(".github.io", 0.50);
    }

    /** 외부에서 prior를 얻는 대표 메서드 */
    public double getTrustPrior(String urlOrHost) {
        String host = normalizeHost(urlOrHost);
        if (host == null || host.isEmpty()) return DEFAULT_PRIOR;

        // 1) exact 매칭 (가장 우선)
        Double ex = exactScores.get(host);
        if (ex != null) return clamp(ex);

        // 2) suffix 매칭 (가장 긴 것 우선)
        Double sufScore = matchLongestSuffix(host, suffixScores);
        if (sufScore != null) return clamp(sufScore);

        // 3) 기본값
        return DEFAULT_PRIOR;
    }

    /** 이 호스트가 (간단 휴리스틱상) 뉴스/미디어 성격인지 */
    public boolean isNewsDomain(String urlOrHost) {
        String host = normalizeHost(urlOrHost);
        if (host == null) return false;
        if (newsSuffixSet.contains(host)) return true;
        for (String sfx : newsSuffixSet) {
            if (host.endsWith(sfx)) return true;
        }
        // 추가 휴리스틱: host에 "news" 포함 + 포털/언론 서픽스
        return host.contains("news.") ||
                host.contains(".news.") ||
                host.startsWith("news-") ||
                host.contains("-news.");
    }

    /** 이 호스트가 (간단 휴리스틱상) 소셜(1차 출처)인지 */
    public boolean isSocialDomain(String urlOrHost) {
        String host = normalizeHost(urlOrHost);
        if (host == null) return false;
        for (String sfx : socialSuffixSet) {
            if (host.equals(sfx) || host.endsWith("." + sfx)) {
                return true;
            }
        }
        return false;
    }

    /** 입력이 URL이든 호스트든 받아서 정규화된 host를 반환 */
    public String normalizeHost(String urlOrHost) {
        if (urlOrHost == null || urlOrHost.isBlank()) return null;
        String raw = urlOrHost.trim().toLowerCase(Locale.ROOT);

        String host = raw;
        // URL이면 host만 파싱
        if (raw.contains("://")) {
            try {
                URI uri = new URI(raw);
                if (uri.getHost() != null) host = uri.getHost().toLowerCase(Locale.ROOT);
            } catch (URISyntaxException ignored) { }
        } else if (raw.contains("/")) {
            // "host/path" 형태일 수 있음
            try {
                URI uri = new URI("https://" + raw);
                if (uri.getHost() != null) host = uri.getHost().toLowerCase(Locale.ROOT);
            } catch (URISyntaxException ignored) { }
        }

        // 일반적인 프리픽스 제거
        host = stripCommonSubdomainPrefix(host);
        return host;
    }

    /** 도메인 prior를 다른 신호(팩트 매치, 페이지 권위 등)와 적절히 블렌딩할 때 사용할 헬퍼(선택 사용) */
    public double blendWithSignals(double prior, boolean factMatched, double pageAuthority01) {
        // prior(도메인) 60%, 팩트매치 25%, 페이지권위 15% 가중치 예시
        double fact = factMatched ? 1.0 : 0.0;
        double blended = (prior * 0.60) + (fact * 0.25) + (pageAuthority01 * 0.15);
        return clamp(blended);
    }

    // ------------------------ 내부 유틸 ------------------------

    private void putExact(String host, double score) {
        exactScores.put(stripCommonSubdomainPrefix(host.toLowerCase(Locale.ROOT)), clamp(score));
    }

    private void putSuffix(String suffix, double score) {
        // suffix는 반드시 ".example.com" 형태로 관리
        String sfx = suffix.toLowerCase(Locale.ROOT);
        if (!sfx.startsWith(".")) sfx = "." + sfx;
        suffixScores.put(sfx, clamp(score));
    }

    private static String stripCommonSubdomainPrefix(String host) {
        if (host == null) return null;
        String h = host;
        for (String pref : List.of("www.", "m.", "mobile.", "amp.")) {
            if (h.startsWith(pref)) {
                h = h.substring(pref.length());
                break; // 한 번만 제거(과제 단순화)
            }
        }
        return h;
    }

    private static Double matchLongestSuffix(String host, Map<String, Double> table) {
        // 가장 긴 suffix가 우선 — 예: ".news.naver.com" > ".naver.com"
        Double best = null;
        int bestLen = -1;
        for (Map.Entry<String, Double> e : table.entrySet()) {
            String sfx = e.getKey();
            if (host.endsWith(sfx.substring(1)) || host.equals(sfx.substring(1))) {
                int len = sfx.length();
                if (len > bestLen) {
                    bestLen = len;
                    best = e.getValue();
                }
            }
        }
        return best;
    }

    private static double clamp(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}