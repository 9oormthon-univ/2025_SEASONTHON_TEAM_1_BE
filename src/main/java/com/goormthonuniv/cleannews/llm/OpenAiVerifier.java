package com.goormthonuniv.cleannews.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goormthonuniv.cleannews.dto.Evidence;
import com.goormthonuniv.cleannews.dto.FeedVerificationRequest;
import com.goormthonuniv.cleannews.dto.VerificationResponse;
import com.goormthonuniv.cleannews.verify.DomainTrustPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ConditionalOnProperty(name = "cleannews.mode", havingValue = "llm")
@Component
@RequiredArgsConstructor
public class OpenAiVerifier {

    @Value("${cleannews.ai.openai.apiKey:}")
    private String apiKey;

    @Value("${cleannews.ai.openai.model:gpt-4o-mini}")
    private String model;

    private final ObjectMapper om = new ObjectMapper();
    private final DomainTrustPolicy trust = new DomainTrustPolicy();

    public VerificationResponse verify(FeedVerificationRequest req) {
        try {
            if (apiKey == null || apiKey.isBlank()) {
                return fail("OPENAI_API_KEY 미설정");
            }

            String system = Prompt.SYSTEM;
            String user = Prompt.user(req);

            var body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)
                    ),
                    "temperature", 0.1,
                    "response_format", Map.of("type","json_object")
            );

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                    .build();

            HttpResponse<String> httpRes = HttpClient.newHttpClient()
                    .send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (httpRes.statusCode() / 100 != 2) {
                return fail("OpenAI API error: " + httpRes.statusCode() + " " + httpRes.body());
            }

            var root = om.readTree(httpRes.body());
            var choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                return fail("OpenAI 응답 비정상(choices empty)");
            }
            String content = choices.get(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                return fail("OpenAI content empty");
            }

            GptResult r = om.readValue(content, GptResult.class);

            List<Evidence> evs = new ArrayList<>();
            if (r.evidences != null) {
                for (GptEvidence ge : r.evidences) {
                    String domain = safeDomain(ge.url);
                    double prior = trust.getTrustPrior(domain);
                    OffsetDateTime ts = null;
                    try { if (ge.publishedAt != null) ts = OffsetDateTime.parse(ge.publishedAt); } catch (Exception ignored) {}

                    evs.add(new Evidence(
                            ge.source == null ? "web" : ge.source,
                            domain,
                            safe(ge.title),
                            safe(ge.url),
                            safe(ge.snippet),
                            ts,
                            0.0,
                            prior
                    ));
                }
            }

            String verdict = safe(r.verdict);
            int conf = Math.max(1, Math.min(100, r.confidence));

            return new VerificationResponse(
                    verdict.isBlank() ? "UNSURE" : verdict,
                    conf == 0 ? 35 : conf,
                    safe(r.rationale),
                    safe(r.consensusSummary),
                    safe(r.normalizedText),
                    evs
            );
        } catch (Exception e) {
            return fail("LLM verify exception: " + e.getMessage());
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String safeDomain(String url) {
        try { var h = URI.create(url).getHost(); return h != null && h.startsWith("www.") ? h.substring(4) : h; }
        catch (Exception e) { return ""; }
    }
    private VerificationResponse fail(String msg) {
        return new VerificationResponse(
                "UNSURE", 30,
                "• LLM-only 경로 오류: " + msg,
                "관련 레퍼런스를 충분히 찾지 못했습니다.",
                "",
                List.of()
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GptResult {
        public String verdict;
        public int confidence;
        public String rationale;
        public String consensusSummary;
        public String normalizedText;
        public List<GptEvidence> evidences;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GptEvidence {
        public String source;
        public String domain;
        public String title;
        public String url;
        public String snippet;
        public String publishedAt;
    }

    static class Prompt {
        static final String SYSTEM = """
        당신은 한국어로 답하는 엄격한 팩트체커입니다. 반드시 웹 검색/브라우징 도구를 사용해 교차검증 가능한 출처(공식 예매처, 주최측/아티스트 공식 채널, 주요 언론)를 3개 이상 확보해야 합니다.

        판정 규칙:
        1. 공식 예매처(예: interpark, yes24) 또는 주최측/언론에서 동일한 날짜·장소·이벤트명이 확인되면 verdict="LIKELY_TRUE".
        2. 일부 정보만 맞고 나머지가 불확실하면 verdict="UNSURE".
        3. 공식 출처에서 반박되거나 존재하지 않으면 verdict="LIKELY_FALSE".

        confidence 계산:
        - LIKELY_TRUE: 70~100 (출처/일치도에 따라)
        - UNSURE: 41~69
        - LIKELY_FALSE: 1~40
        (숫자는 Orchestrator와 동일하게 1~100 정수로 매핑)

        반드시 다음 JSON 스키마만 출력:
        {
          "verdict": "...",
          "confidence": <int>,
          "rationale": "...",
          "consensusSummary": "...",
          "normalizedText": "...",
          "evidences": [
            {
              "source": "...",
              "domain": "...",
              "title": "...",
              "url": "...",
              "snippet": "...",
              "publishedAt": "..."
            }
          ]
        }
        """;
        static String user(FeedVerificationRequest req) {
            return """
            플랫폼: %s
            소스 URL: %s
            언어: %s
            제목: %s
            본문: %s
            이미지: %s
            """.formatted(
                    nn(req.platform()), nn(req.sourceUrl()), nn(req.language()),
                    nn(req.title()), nn(req.text()), String.valueOf(req.imageUrls())
            );
        }
        private static String nn(String s){ return s==null?"":s; }
    }
}