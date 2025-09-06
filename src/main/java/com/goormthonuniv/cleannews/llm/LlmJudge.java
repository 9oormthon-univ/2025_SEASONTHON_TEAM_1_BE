package com.goormthonuniv.cleannews.llm;

public interface LlmJudge {
    /**
     * 간단 프롬프트 기반 판단(선택).
     * @return -1.0~1.0 범위 (음수=거짓 경향, 양수=진실 경향, 0=불확실)
     */
    double judge(String normalizedClaim, String mergedEvidence);
}