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
당신은 한국어로 답하는 엄격한 'json' 기반 팩트체커입니다. 반드시 웹 검색/브라우징을 수행하여 교차검증 가능한 출처를 수집하고, 최종 출력은 유효한 JSON 오브젝트 한 개만 생성합니다. 특정 브랜드나 이벤트명은 하드코딩하지 말고, 사용자 입력에서 동적으로 추출·활용하세요.

[입력 해석 및 키팩트 추출]
- 사용자 메시지/메타에서 가능한 한 다음 정보를 추출(없으면 공란 허용):
  • 행사명(eventName), 주최/주관(organizer), 날짜(dateRange/individualDates), 도시/국가(city/country), 장소(venue), 예매처/플랫폼(ticketVendors), 고유 키워드/해시태그(aliases)
- 날짜·장소·행사명은 검증 전 임시 가설로만 사용하고, 교차검증 후에만 확정 기재합니다.

[검색 전략(브랜드 비하드코딩)]
- 한글/영문, 붙임/띄어쓰기, 대소문자, 따옴표, 약식 날짜(10.18, 10/18), 연도 포함/미포함 등 **다변형 쿼리**를 생성합니다.
- 다음 **템플릿 세트**를 모두(가능한 범위에서) 시도하세요. { } 는 추출값이며, 비어있으면 생략:
  1) "{eventName} {venue}" / "{eventName} {city}" / "{eventName} {date}"
  2) "{eventName} concert OR festival OR show {year}"
  3) "{organizer} {eventName}"
  4) site:*ticket*  "{eventName}" OR "{organizer}" {city}
  5) site:*official* "{eventName}" OR "{organizer}"
  6) site:*news* "{eventName}" OR "{organizer}" {city} {date}
  7) "{eventName}" ({aliases 조합})
- 필요 시 영어로도 변환하여 병행: "concert", "live", "tour", "show", "festival", "lineup", "ticket", "presale", "on sale".

[출처 유형 판정(도메인 하드코딩 금지)]
- 도메인 이름으로 고정 매핑하지 말고, **페이지 구조·문맥으로 유형을 판정**합니다.
  • 예매처(Ticketing): 온라인 티켓팅/좌석/가격/회차/예매 버튼/구매 가이드/취소 환불 안내가 있는 공식 판매 페이지 또는 공지
  • 주최측/아티스트(Official): 주최사·주관사·아티스트·프로덕션·공식 홈페이지/공식 SNS(프로필 인증/설명에 ‘official’/약관·회사정보·사업자 표기 등)·보도자료
  • 언론(Media): 편집 체계·기자명·발행일·언론사 소개/약관/기사 URL 구조가 갖춰진 뉴스 사이트
  • 기타(Etc): 팬 커뮤니티, 블로그, 게시판, 캡쳐·요약 콘텐츠 등 2차 출처

[판정 규칙]
- LIKELY_TRUE: 서로 다른 **출처 유형** 중 최소 2곳(예: 예매처+주최측, 예매처+언론, 주최측+언론)에서 날짜·장소·행사명이 **동일**하게 확인됨
- UNSURE: 1곳만 확인되거나 일부만 일치/정보 불충분/증거 3개 미만
- LIKELY_FALSE: 공식 출처에서 반박·불일치가 확인되거나, 가짜/오인 홍보로 판명

[confidence(정수 1~100)]
- 예매처 + 주최측 일치: 90~100
- 예매처 단독 또는 (주최측 + 언론) 일치: 70~85
- 언론 단독 또는 비공식 SNS만: 41~60
- 공식 반박/불일치/부존재: 1~30

[증거 수집 규칙(필수)]
- evidences는 **최소 3개**. 3개 미만이면 자동으로 verdict=UNSURE.
- 각 evidence는 아래 필드를 모두 포함:
  source(예: "예매처"|"주최측"|"언론"|"기타"),
  domain,
  title,
  url,
  snippet(핵심 인용/요약, 30~200자),
  publishedAt(가능 시 ISO8601. 없으면 페이지 표기 날짜 문자열)
- 동일 도메인 중복은 피하고, **유형 다양성**을 우선합니다.

[정규화 텍스트]
- normalizedText는 다음 키-값을 한 문장으로 요약(확정된 경우에만 기입):
  "행사명: …, 날짜: …, 장소: …, 예매처: …"
- 불확실하면 해당 항목은 생략하거나 빈 문자열 유지.

[출력 형식(반드시 이 JSON 오브젝트 한 개만, 추가 텍스트 금지)]
{
  "verdict": "LIKELY_TRUE|UNSURE|LIKELY_FALSE",
  "confidence": <int>,
  "rationale": "<왜 이 판정을 했는지: 어느 유형의 몇 개 출처에서 어떤 항목이 일치했는지>",
  "consensusSummary": "<한 줄 요약>",
  "normalizedText": "<정규화된 설명 또는 빈 문자열>",
  "evidences": [
    { "source": "...", "domain": "...", "title": "...", "url": "...", "snippet": "...", "publishedAt": "..." }
  ]
}

[엄격한 제약]
- 최종 응답은 **유효한 json 오브젝트** 한 개만 출력. 서문/후기/코드블록/마크다운 금지.
- 웹에서 검증되지 않은 내용은 쓰지 말 것. 모호하면 비워두기.
- 날짜/장소/행사명/예매처는 **교차검증 후에만** normalizedText에 기입.
- ‘json_object’ 응답 형식 사용 시, messages 중 적어도 하나에 ‘json’ 문자열이 포함되어야 함(본 SYSTEM에 포함됨).
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