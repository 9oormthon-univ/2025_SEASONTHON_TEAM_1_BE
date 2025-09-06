package com.goormthonuniv.cleannews.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.goormthonuniv.cleannews.dto.Evidence;
import com.goormthonuniv.cleannews.dto.FeedVerificationRequest;
import com.goormthonuniv.cleannews.dto.VerificationResponse;
import com.goormthonuniv.cleannews.llm.LlmJudge;
import com.goormthonuniv.cleannews.llm.OpenAiVerifier;
import com.goormthonuniv.cleannews.search.SearchAdapter;
import com.goormthonuniv.cleannews.search.SearchResult;
import com.goormthonuniv.cleannews.util.TextUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VerificationOrchestrator {

    // ===== 모드 스위치 =====
    // application.yml:
    // cleannews:
    //   mode: llm   # llm | hybrid (default=hybrid)
    @Value("${cleannews.mode:hybrid}")
    private String mode;

    // ===== 의존성 =====
    private final List<SearchAdapter> adapters;
    private final KeywordService keywordService;
    private final SimilarityService similarityService;
    private final ObjectProvider<LlmJudge> llmJudgeProvider;
    private final OpenAiVerifier openAiVerifier; // LLM-only 경로

    // ===== 캐시 =====
    private final Cache<String, List<SearchResult>> searchCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15))
            .maximumSize(2000)
            .build();

    /** 메인 엔트리 */
    public VerificationResponse verify(FeedVerificationRequest req) {

        // ---- LLM-only 모드: 검색 어댑터 사용하지 않고 GPT가 직접 서칭/검증 ----
        if ("llm".equalsIgnoreCase(mode)) {
            return openAiVerifier.verify(req);
        }

        // ---- hybrid(기존) 경로 ----
        // 1) 입력 정규화 (final로 딱 한 번만 할당)
        final String normalized = normalizeInput(req);

        // 2) 키워드 부스트 & 쿼리 구성
        var keywords = keywordService.boostedKeywords(req.title(), req.text(), req.sourceUrl(), 12);
        String query = keywordService.buildQuery(keywords);

        // 3) 1차 검색
        List<SearchResult> hits = searchCache.get(query, q -> runSearch(q, 8));
        System.out.printf("[CleanNews] query=\"%s\" hits=%d%n", query, hits == null ? 0 : hits.size());

        // 4) 폴백 재검색
        if (hits == null || hits.isEmpty()) {
            LinkedHashSet<String> candidates = new LinkedHashSet<>();

            if (req.title() != null && !req.title().isBlank()) {
                candidates.add("\"" + req.title().trim() + "\"");
            }
            if (req.text() != null && req.text().strip().length() >= 10) {
                String t = req.text().strip();
                if (t.length() > 60) t = t.substring(0, 60);
                candidates.add("\"" + t + "\"");
            }

            List<String> triggers = List.of("이벤트", "프로모션", "공지", "공식", "모집", "무료", "당첨", "체험단");
            for (String ent : keywords) {
                if (ent.matches("[a-z0-9_\\.]{2,}") || ent.matches("[가-힣]{2,}")) {
                    for (String tr : triggers) {
                        candidates.add(ent + " " + tr);
                    }
                }
            }

            try {
                String host = URI.create(Objects.toString(req.sourceUrl(), "")).getHost();
                if (host != null && !host.isBlank()) {
                    candidates.add("site:" + host + " 공지");
                    candidates.add("site:" + host + " 이벤트");
                }
            } catch (Exception ignored) {}

            if (keywords.size() >= 2) candidates.add(keywords.get(0) + " " + keywords.get(1));
            if (keywords.size() >= 3) candidates.add(keywords.get(0) + " " + keywords.get(2));

            for (String q2 : candidates) {
                var more = runSearch(q2, 8);
                System.out.printf("[CleanNews] fallback query=\"%s\" hits=%d%n", q2, more.size());
                if (!more.isEmpty()) {
                    hits = more;
                    break;
                }
            }
        }

        // 5) 증거 집계
        List<Evidence> evidences = (hits == null ? List.<SearchResult>of() : hits).stream()
                .map(h -> {
                    String cmp = (safe(h.title()) + " " + safe(h.snippet())).toLowerCase(Locale.ROOT);
                    double sim = similarityService.cosine(normalized, cmp);
                    String domain = extractDomain(h.url());
                    double prior = similarityService.trustPrior(domain);
                    return new Evidence(h.source(), domain, h.title(), h.url(), h.snippet(), h.publishedAt(), sim, prior);
                })
                .sorted(Comparator.comparingDouble(Evidence::similarity).reversed())
                .limit(6)
                .toList();

        if (evidences.isEmpty()) {
            return new VerificationResponse(
                    "UNSURE",
                    30,
                    "• 레퍼런스 검색 결과가 부족합니다(검색 엔진/쿼리/설정 확인 필요).",
                    "관련 레퍼런스를 충분히 찾지 못했습니다.",
                    normalized,
                    List.of()
            );
        }

        // 6) (선택) LLM 보정
        double llmScore = 0.0;
        LlmJudge judge = llmJudgeProvider.getIfAvailable();
        if (judge != null) {
            String merged = evidences.stream()
                    .map(e -> "- " + e.title() + " :: " + e.snippet())
                    .collect(Collectors.joining("\n"));
            try {
                llmScore = judge.judge(normalized, merged); // -1.0 ~ 1.0
            } catch (Exception e) {
                System.out.println("[CleanNews] LLM judge error: " + e.getMessage());
            }
        }

        // 7) 점수화 및 판정
        double simAvg   = evidences.stream().limit(3).mapToDouble(Evidence::similarity).average().orElse(0);
        double priorAvg = evidences.stream().limit(3).mapToDouble(Evidence::trustPrior).average().orElse(0.5);

        double llmTerm = (judge != null) ? ((llmScore + 1.0) / 2.0) * 0.3 : 0.0;
        double raw = (simAvg * 0.7) + (priorAvg * 0.2) + llmTerm;
        raw = Math.max(0.0, Math.min(1.0, raw));
        int confidence = (int) Math.round(raw * 100);

        String verdict = confidence >= 70 ? "LIKELY_TRUE"
                : (confidence <= 40 ? "LIKELY_FALSE" : "UNSURE");

        String rationale = """
                • 키워드: %s
                • 유사도 평균: %.2f
                • 출처 신뢰도 평균: %.2f
                • LLM 보정 사용: %s
                """.formatted(String.join(", ", keywords),
                simAvg, priorAvg, (judge != null ? "yes" : "no")).strip();

        String consensus = makeConsensusSummary(evidences);

        return new VerificationResponse(verdict, confidence, rationale, consensus, normalized, evidences);
    }

    // ===================== 내부 유틸 =====================

    /** 입력 텍스트를 한 번만 정규화하여 final 변수로 사용할 수 있게 반환 */
    private static String normalizeInput(FeedVerificationRequest req) {
        String first = TextUtils.normalize(Objects.toString(req.text(), ""));
        if (!first.isBlank()) return first;

        String fallback = TextUtils.normalize(
                (req.title() != null ? req.title() + " " : "") +
                        (req.sourceUrl() != null ? req.sourceUrl() : "")
        );
        return fallback;
    }

    /** 다중 어댑터 검색 + dedupe + 최신/간결 우선 정렬 */
    private List<SearchResult> runSearch(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        List<SearchResult> all = new ArrayList<>();
        for (SearchAdapter a : adapters) {
            try {
                all.addAll(a.search(query, limit));
            } catch (Exception e) {
                System.out.printf("[CleanNews] adapter=%s error=%s%n", a.name(), e.getMessage());
            }
        }
        Map<String, SearchResult> map = new LinkedHashMap<>();
        for (SearchResult r : all) {
            map.putIfAbsent(safe(r.url()), r);
        }
        return map.values().stream()
                .sorted((x, y) -> {
                    OffsetDateTime dx = x.publishedAt();
                    OffsetDateTime dy = y.publishedAt();
                    int cmp = 0;
                    if (dx != null && dy != null) cmp = dy.compareTo(dx);
                    if (cmp != 0) return cmp;
                    return Integer.compare(safeLen(x.title()), safeLen(y.title()));
                })
                .limit(limit)
                .toList();
    }

    private static String extractDomain(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return null; }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static int safeLen(String s) { return s == null ? 9999 : s.length(); }

    private String makeConsensusSummary(List<Evidence> evs) {
        if (evs == null || evs.isEmpty()) return "관련 레퍼런스를 충분히 찾지 못했습니다.";
        var top = evs.stream().limit(3).map(Evidence::title).toList();
        return "상위 출처 요약: " + String.join(" / ", top);
    }
}