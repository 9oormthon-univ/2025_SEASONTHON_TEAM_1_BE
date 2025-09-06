package com.goormthonuniv.cleannews.dto;

import java.util.List;

public record VerificationResponse(
        String verdict,          // LIKELY_TRUE | LIKELY_FALSE | UNSURE
        int confidence,          // 0~100
        String rationale,        // 판단 근거 요약
        String consensusSummary, // 상위 레퍼런스 종합 한줄 요약(간이)
        String normalizedText,   // 정규화된 피드 텍스트(최종 비교 대상)
        List<Evidence> evidences // 상위 N개 근거
) {}