package com.webtestpro.api.controller;

import com.webtestpro.api.dto.request.LoginRequest;
import com.webtestpro.api.dto.request.RefreshTokenRequest;
import com.webtestpro.api.dto.response.TokenResponse;
import com.webtestpro.api.service.AuthService;
import com.webtestpro.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证", description = "登录/登出/刷新 Token")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户名密码登录")
    @PostMapping("/login")
    public Result<TokenResponse> login(@RequestBody @Validated LoginRequest request,
                                       HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);
        return Result.ok(authService.login(request, clientIp));
    }

    @Operation(summary = "登出（使当前 Token 失效）")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    @Operation(summary = "使用 refresh_token 换取新 access_token")
    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@RequestBody @Validated RefreshTokenRequest request) {
        return Result.ok(authService.refresh(request));
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
