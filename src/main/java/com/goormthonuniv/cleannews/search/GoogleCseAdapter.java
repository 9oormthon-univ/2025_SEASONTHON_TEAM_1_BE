package com.goormthonuniv.cleannews.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

@Component
public class GoogleCseAdapter implements SearchAdapter {

    private final RestClient rest;
    private final String endpoint;
    private final String apiKey;
    private final String cx;

    public GoogleCseAdapter(RestClient rest,
                            @Value("${cleannews.adapters.google.endpoint}") String endpoint,
                            @Value("${cleannews.adapters.google.apiKey:}") String apiKey,
                            @Value("${cleannews.adapters.google.cx:}") String cx) {
        this.rest = rest;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.cx = cx;
    }

    @Override public String name() { return "google_cse"; }

    @Override
    public List<SearchResult> search(String query, int limit) {
        if (apiKey == null || apiKey.isBlank() || cx == null || cx.isBlank()) {
            System.out.println("[CleanNews] GoogleCSE disabled or misconfigured (missing apiKey/cx)");
            return List.of();
        }
        try {
            String encodedQ = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "%s?key=%s&cx=%s&q=%s&num=%d&gl=kr&lr=lang_ko&hl=ko&safe=off"
                    .formatted(endpoint, apiKey, cx, encodedQ, Math.min(limit, 10));

            Map<String, Object> res = rest.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (res == null) return List.of();

            Object raw = res.get("items");
            List<?> list = (raw instanceof List<?> l) ? l : Collections.emptyList();

            List<SearchResult> out = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?,?> m)) continue;

                String title   = Optional.ofNullable(m.get("title")).map(Object::toString).orElse("");
                String link    = Optional.ofNullable(m.get("link")).map(Object::toString).orElse("");
                String snippet = Optional.ofNullable(m.get("snippet")).map(Object::toString).orElse("");

                out.add(new SearchResult(name(), title, link, snippet, (OffsetDateTime) null));
            }
            return out;
        } catch (Exception e) {
            System.out.println("[CleanNews] GoogleCSE error: " + e.getMessage());
            return List.of();
        }
    }
}
