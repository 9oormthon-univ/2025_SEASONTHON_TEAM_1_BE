package com.goormthonuniv.cleannews.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record FeedVerificationRequest(
        @NotBlank String platform,    // "instagram" | "facebook" | "naver_news" | ...
        @NotBlank String sourceUrl,   // 원문 링크(가능하면)
        String language,              // "ko"|"en"|...
        String title,                 // 선택
        String text,                  // 게시물 본문(최대한 전달)
        List<String> imageUrls        // 선택
) {}