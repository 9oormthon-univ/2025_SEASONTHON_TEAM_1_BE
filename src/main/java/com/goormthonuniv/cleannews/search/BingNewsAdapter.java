package com.goormthonuniv.cleannews.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.core.ParameterizedTypeReference;

import java.net.URLEncoder;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;

@Component
public class BingNewsAdapter implements SearchAdapter {

    private final RestClient rest;
    private final String endpoint;
    private final String apiKey;

    public BingNewsAdapter(RestClient rest,
                           @Value("${cleannews.adapters.bing.endpoint}") String endpoint,
                           @Value("${cleannews.adapters.bing.apiKey:}") String apiKey) {
        this.rest = rest;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    @Override public String name() { return "bing"; }

    @Override
    public List<SearchResult> search(String query, int limit) {
        if (apiKey == null || apiKey.isBlank()) return List.of();
        try {
            URI uri = URI.create(endpoint + "?q=" + URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                    + "&count=" + limit);

            Map<String, Object> res = rest.get().uri(uri)
                    .header("Ocp-Apim-Subscription-Key", apiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (res == null) return List.of();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> value =
                    (List<Map<String, Object>>) res.getOrDefault("value", Collections.<Map<String,Object>>emptyList());

            List<SearchResult> out = new ArrayList<>();
            for (Map<String, Object> v : value) {
                String name = (String) v.getOrDefault("name", "");
                String url  = (String) v.getOrDefault("url", "");
                String desc = (String) v.getOrDefault("description", "");
                OffsetDateTime odt = null;
                Object dateObj = v.get("datePublished");
                if (dateObj instanceof String s && !s.isBlank()) {
                    try { odt = OffsetDateTime.parse(s); } catch (Exception ignore) {}
                }
                out.add(new SearchResult(name(), name, url, desc, odt));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}