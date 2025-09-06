package com.goormthonuniv.cleannews.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.core.ParameterizedTypeReference;

import java.net.URLEncoder;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;

@Component
public class NaverNewsAdapter implements SearchAdapter {

    private final RestClient rest;
    private final String endpoint;
    private final String clientId;
    private final String clientSecret;

    public NaverNewsAdapter(RestClient rest,
                            @Value("${cleannews.adapters.naver.endpoint}") String endpoint,
                            @Value("${cleannews.adapters.naver.clientId:}") String clientId,
                            @Value("${cleannews.adapters.naver.clientSecret:}") String clientSecret) {
        this.rest = rest;
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override public String name() { return "naver"; }

    @Override
    public List<SearchResult> search(String query, int limit) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) return List.of();
        try {
            String url = "%s?query=%s&display=%d&sort=sim".formatted(
                    endpoint, URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8), Math.min(limit, 10));

            Map<String, Object> res = rest.get().uri(URI.create(url))
                    .header("X-Naver-Client-Id", clientId)
                    .header("X-Naver-Client-Secret", clientSecret)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            List<Map<String, Object>> items =
                    (List<Map<String, Object>>) res.getOrDefault("items", Collections.<Map<String,Object>>emptyList());

            List<SearchResult> out = new ArrayList<>();
            for (Map<String, Object> it : items) {
                String title = stripTags((String) it.getOrDefault("title",""));
                String link  = (String) it.getOrDefault("link","");
                String desc  = stripTags((String) it.getOrDefault("description",""));
                out.add(new SearchResult(name(), title, link, desc, null));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "");
    }
}