package com.lusuoria.settlement.config;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 未认证（没有 token / token 过期 / token 无效）时的统一响应。
 *
 * Spring Security 默认行为：anyRequest().authenticated() 拦下未认证请求时，
 * 如果没有自定义 AuthenticationEntryPoint，会返回 403（Forbidden），
 * 这会导致前端无法区分"未登录/登录已过期"和"已登录但角色权限不足"两种情况，
 * 表现为：token 过期后用户仍停留在已登录页面，操作时只提示"无权限"而不会跳转登录页。
 *
 * 这里显式返回 401（Unauthorized），前端据此跳转登录页；
 * 403（Forbidden）则保留给"已认证但角色权限不足"的场景（见 RoleUtil / @PreAuthorize）。
 */
@Component
public class JwtAuthEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"登录已过期，请重新登录\",\"data\":null}");
    }
}
