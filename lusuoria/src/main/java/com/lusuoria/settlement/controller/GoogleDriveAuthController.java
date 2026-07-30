package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.GoogleDriveAuth;
import com.lusuoria.settlement.service.impl.GoogleDriveAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Google Drive OAuth 授权（数据库每日备份用，见 GoogleDriveAuthService 类注释）。
 *
 * callback 这个接口必须是免登录的（Google 直接跳转浏览器过来，不带我们系统的 JWT），
 * 见 SecurityConfig 里对 /api/google-drive-auth/callback 的放行配置；安全性靠 state 参数
 * 一次性校验，不是靠接口本身鉴权。
 */
@RestController
@RequestMapping("/api/google-drive-auth")
public class GoogleDriveAuthController {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveAuthController.class);

    @Autowired private GoogleDriveAuthService authService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @GetMapping("/authorize-url")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<String> authorizeUrl() {
        return ApiResponse.success(authService.authorizeUrl());
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<GoogleDriveAuth> status() {
        return ApiResponse.success(authService.currentAuth());
    }

    @GetMapping("/callback")
    public void callback(@RequestParam String code, @RequestParam String state,
                          HttpServletResponse response) throws IOException {
        String target = frontendUrl + "/users";
        try {
            authService.exchangeCodeForTokens(code, state);
            target += "?googleDriveConnected=1";
        } catch (Exception e) {
            // 这个接口是 Google 直接重定向过来的（见类注释），失败时是靠拼错误信息到跳转链接上
            // 展示给用户，不会走 GlobalExceptionHandler（异常在这里已经被吃掉，不会再往外抛），
            // 之前这里完全没打日志——如果不在这里手动记一下，Render 日志里就永远查不到这次
            // 授权到底是哪里失败的，只能看到用户反馈"连接失败"这一句话
            log.error("Google Drive 授权回调失败：{}", e.toString(), e);
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            target += "?googleDriveConnectError=" + URLEncoder.encode(message, StandardCharsets.UTF_8.name());
        }
        response.sendRedirect(target);
    }
}
