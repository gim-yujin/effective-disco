package com.effectivedisco.controller;

import com.effectivedisco.dto.request.LoginRequest;
import com.effectivedisco.dto.request.SignupRequest;
import com.effectivedisco.dto.response.AuthResponse;
import com.effectivedisco.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "회원가입·로그인 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "회원가입", description = "username, email, password로 계정을 생성하고 JWT 토큰을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "회원가입 성공 — JWT 토큰 및 username 반환"),
        @ApiResponse(responseCode = "400", description = "입력값 유효성 실패 또는 username/email 중복")
    })
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.ok(authService.signup(request));
    }

    @Operation(summary = "로그인", description = "username, password로 인증하고 JWT 토큰을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "로그인 성공 — JWT 토큰 및 username 반환"),
        @ApiResponse(responseCode = "400", description = "입력값 유효성 실패"),
        @ApiResponse(responseCode = "401", description = "인증 실패 (username 또는 password 불일치)")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}
