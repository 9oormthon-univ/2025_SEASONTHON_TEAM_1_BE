package com.goormthonuniv.cleannews.dto;

import java.time.OffsetDateTime;

public record Evidence(
        String source,            // "bing" | "google_cse" | "naver"
        String domain,            // 예: "news.naver.com"
        String title,
        String url,
        String snippet,
        OffsetDateTime publishedAt,
        double similarity,        // 0.0~1.0 텍스트 유사도(간이)
        double trustPrior         // 0.0~1.0 도메인 사전 신뢰 점수(간이)
) {}