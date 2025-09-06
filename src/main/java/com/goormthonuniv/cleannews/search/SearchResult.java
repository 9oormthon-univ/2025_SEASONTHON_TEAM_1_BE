package com.goormthonuniv.cleannews.search;

import java.time.OffsetDateTime;

public record SearchResult(
        String source,      // 어댑터명
        String title,
        String url,
        String snippet,
        OffsetDateTime publishedAt
) {}