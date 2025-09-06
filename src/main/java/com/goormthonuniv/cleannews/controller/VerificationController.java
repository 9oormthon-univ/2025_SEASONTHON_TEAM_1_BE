package com.goormthonuniv.cleannews.controller;

import com.goormthonuniv.cleannews.dto.FeedVerificationRequest;
import com.goormthonuniv.cleannews.dto.VerificationResponse;
import com.goormthonuniv.cleannews.service.VerificationOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationOrchestrator orchestrator;

    @Operation(summary = "피드 사실 검증", description = "피드(텍스트/이미지/링크)를 전달하면 검증 결과/신뢰도/레퍼런스를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검증 성공"),
            @ApiResponse(responseCode = "400", description = "요청 형식 오류"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/verify")
    public ResponseEntity<VerificationResponse> verify(@Valid @RequestBody FeedVerificationRequest req) {
        VerificationResponse res = orchestrator.verify(req);
        return ResponseEntity.ok(res);
    }
}