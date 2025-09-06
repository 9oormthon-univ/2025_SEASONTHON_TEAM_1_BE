package com.goormthonuniv.cleannews.service;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SimilarityService {

    // 간단한 TF-Cosine
    public double cosine(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return 0.0;
        Map<String, Integer> va = tf(a);
        Map<String, Integer> vb = tf(b);
        Set<String> union = new HashSet<>(va.keySet());
        union.addAll(vb.keySet());
        double dot = 0, na = 0, nb = 0;
        for (String k : union) {
            int xa = va.getOrDefault(k, 0);
            int xb = vb.getOrDefault(k, 0);
            dot += (double) xa * xb;
            na  += (double) xa * xa;
            nb  += (double) xb * xb;
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private Map<String, Integer> tf(String text) {
        Map<String, Integer> m = new HashMap<>();
        for (String t : text.split("\\s+")) {
            if (t.length() < 2) continue;
            m.merge(t, 1, Integer::sum);
        }
        return m;
    }

    // 매우 간단한 도메인 신뢰도(데모)
    public double trustPrior(String domain) {
        if (domain == null) return 0.5;
        String d = domain.toLowerCase(Locale.ROOT);
        if (d.contains("reuters") || d.contains("apnews") || d.contains("nytimes") || d.contains("bbc")) return 0.9;
        if (d.contains("news.naver") || d.contains("hani") || d.contains("joongang") || d.contains("chosun")) return 0.7;
        return 0.5;
    }
}