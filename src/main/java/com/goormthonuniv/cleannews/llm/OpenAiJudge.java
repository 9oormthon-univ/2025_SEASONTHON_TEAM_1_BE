package com.goormthonuniv.cleannews.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class OpenAiJudge implements LlmJudge {

    private final RestClient rest;
    private final String apiKey;
    private final String model;
    private final String provider;

    public OpenAiJudge(RestClient.Builder builder,                    // ✅ Builder 주입
                       @Value("${cleannews.ai.openai.apiKey:}") String apiKey,
                       @Value("${cleannews.ai.openai.model:gpt-4o-mini}") String model,
                       @Value("${cleannews.ai.provider:none}") String provider) {
        this.rest = builder.build();                                  // ✅ 여기서 build()
        this.apiKey = apiKey;
        this.model = model;
        this.provider = provider;
    }

    @Override
    public double judge(String claim, String evidence) {
        if (!"openai".equalsIgnoreCase(provider) || apiKey == null || apiKey.isBlank()) return 0.0;

        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role","system","content",
                                    "You are a cautious fact-checking assistant. Return a single number between -1.0 and 1.0: negative means likely false, positive means likely true, near 0 means unsure."),
                            Map.of("role","user","content",
                                    "CLAIM:\n" + claim + "\n\nEVIDENCE SNIPPETS:\n" + evidence + "\n\nReturn ONLY the number.")
                    ),
                    "temperature", 0
            );

            Map<?,?> res = rest.post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(Map.class);

            var choices = (List<Map<String, Object>>) res.get("choices");
            if (choices == null || choices.isEmpty()) return 0.0;
            String txt = ((Map<String, String>) choices.get(0).get("message")).get("content");
            return Double.parseDouble(txt.trim());
        } catch (Exception e) {
            return 0.0;
        }
    }
}