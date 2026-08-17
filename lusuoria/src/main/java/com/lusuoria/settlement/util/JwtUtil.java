package com.lusuoria.settlement.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * JWT 的签发/解析工具类——登录成功后签发 token（generateToken），之后每个请求靠
 * SecurityConfig.jwtAuthFilter 解出 username/role 塞进 SecurityContext，全程不查数据库
 * （见 CLAUDE.md "Auth" 一节）。
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    /** 把配置的 secret 字符串转成签名用的密钥，不够32字节（HS256 最低要求）就右侧补0 */
    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // 确保密钥长度满足 HS256 要求（至少32字节）
        if (keyBytes.length < 32) {
            byte[] paddedKey = new byte[32];
            System.arraycopy(keyBytes, 0, paddedKey, 0, keyBytes.length);
            return Keys.hmacShaKeyFor(paddedKey);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /** 签发一个新 token，username 存在标准的 subject 字段，role 存在自定义 claim 里 */
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .setSubject(username)
                .claim("role", role)
                .setIssuedAt(now)
                .setExpiration(expireDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 从 Token 中获取 Spring Security 格式的权限列表
     * 注意：必须加 "ROLE_" 前缀，@PreAuthorize("hasRole('ADMIN')") 才能正确匹配
     */
    public List<GrantedAuthority> getAuthoritiesFromToken(String token) {
        String role = getRoleFromToken(token);
        if (role != null && !role.isEmpty()) {
            return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return Collections.emptyList();
    }

    /** 解出 token 里的用户名（subject） */
    public String getUsernameFromToken(String token) {
        return getClaims(token).getSubject();
    }

    /** 解出 token 里的 role claim（ADMIN/STAFF/AUDITOR/GUEST） */
    public String getRoleFromToken(String token) {
        return (String) getClaims(token).get("role");
    }

    /** token 是否有效（签名对得上、没过期）；解析失败/异常都当无效，不抛出去 */
    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 验签+解析出 claims，token 无效/过期会抛 JwtException，调用方自己决定要不要兜底 */
    private Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}