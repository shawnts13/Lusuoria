package com.lusuoria.settlement.service.impl;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import com.lusuoria.settlement.entity.GoogleDriveAuth;
import com.lusuoria.settlement.repository.GoogleDriveAuthRepository;
import com.lusuoria.settlement.util.RoleUtil;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Date;
import java.util.Map;

/**
 * Google Drive OAuth 授权（2026-07-29 新增，数据库每日备份上传用，见 {@link DbBackupService}）。
 *
 * 走"个人 Google 账号 OAuth 网页授权"流程（不是 Service Account，原因见 {@link GoogleDriveAuth}
 * 类注释）：
 *   1. 管理层在"账号管理"页面点"连接/重新连接 Google Drive"，前端调 authorizeUrl() 拿到 Google
 *      的授权页面地址，整页跳转过去（不是弹窗，Google 的 OAuth 页面不允许被 iframe/弹窗嵌入）。
 *   2. 管理层用自己的 Google 账号登录+同意授权，Google 跳转回本服务的 /api/google-drive-auth/callback
 *      （这个回调地址必须在 Google Cloud Console 的 OAuth 客户端里配置成"已获授权的重定向 URI"）。
 *   3. callback 用收到的 code 换 access_token+refresh_token，只存 refresh_token（见
 *      exchangeCodeForTokens），然后跳转回前端"账号管理"页面。
 *   4. 之后每次备份，用存的 refresh_token 换新的 access_token（UserCredentials 自动处理，
 *      不需要自己手动刷新）。
 *
 * CSRF 防护：authorizeUrl() 生成的 state 是一次性随机串，存在内存里（单实例部署，没有多实例
 * 同步问题——Render 免费版本身也只有单实例），callback 校验 state 匹配且未过期（10分钟内）才处理，
 * 用过一次就失效，防止别人拿到回调 URL 重放。
 */
@Service
public class GoogleDriveAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveAuthService.class);
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final long STATE_TTL_MS = 10 * 60 * 1000L;

    @Value("${backup.google-oauth.client-id:}") private String clientId;
    @Value("${backup.google-oauth.client-secret:}") private String clientSecret;
    @Value("${backup.google-oauth.redirect-uri:}") private String redirectUri;

    @Autowired private GoogleDriveAuthRepository authRepo;

    /**
     * 自己的懒加载代理引用：persistTokens() 需要经过 Spring 事务代理调用才能让 @Transactional
     * 生效（同一个 bean 内部 this.persistTokens(...) 会绕开代理），跟 InfluencerExcelHandler.self
     * 是同一套写法。
     */
    @Autowired @Lazy private GoogleDriveAuthService self;

    // 连接/读取都给明确超时，避免 Google 那边响应慢时把请求线程无限期挂住
    private final RestTemplate restTemplate = buildRestTemplate();
    // 单实例部署，内存存一下就够；state -> 生成时间戳，callback 校验完立刻删除
    private final Map<String, Long> pendingStates = new java.util.concurrent.ConcurrentHashMap<>();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(15_000);
        return new RestTemplate(factory);
    }

    /** 生成 Google 授权页面 URL，供前端整页跳转 */
    public String authorizeUrl() {
        String state = java.util.UUID.randomUUID().toString();
        pendingStates.put(state, System.currentTimeMillis());
        return UriComponentsBuilder.fromHttpUrl(AUTH_URL)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", DriveScopes.DRIVE)
                .queryParam("access_type", "offline")
                // force：不加这个的话，同一个 Google 账号第二次授权时 Google 经常不会再返回
                // refresh_token（只有第一次同意授权才给），我们需要每次重新连接都稳定拿到新的
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .build().toUriString();
    }

    private void validateState(String state) {
        Long issuedAt = pendingStates.remove(state);
        if (issuedAt == null) throw new RuntimeException("授权链接已过期或已使用，请重新点击连接");
        if (System.currentTimeMillis() - issuedAt > STATE_TTL_MS) {
            throw new RuntimeException("授权链接已过期，请重新点击连接");
        }
    }

    /**
     * callback 收到 code 后换 token 并存库；替换掉旧的一行（软删旧的，插入新的）。
     *
     * 换 token 是同步调用 Google 的网络请求，耗时不可控（对方响应慢/抖动），特意不放进
     * @Transactional 里——如果事务从方法入口就开始，会让这次网络等待占住一个数据库连接，
     * 而这个项目 HikariCP 连接池总共只有 3 个（Render 免费版限制），一次慢请求就可能吃掉
     * 1/3 的连接预算。网络调用完全结束、拿到 token 之后，才通过 persistTokens() 开一个
     * 只包 DB 写入的短事务。
     */
    public void exchangeCodeForTokens(String code, String state) {
        validateState(state);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("redirect_uri", redirectUri);
        body.add("grant_type", "authorization_code");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        TokenResponse resp;
        try {
            resp = restTemplate.postForObject(TOKEN_URL, new HttpEntity<>(body, headers), TokenResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Google 授权码换取 token 失败：" + e.getMessage(), e);
        }
        if (resp == null || resp.getRefreshToken() == null) {
            // Google 在 prompt=consent 时正常一定会返回 refresh_token；万一没有，通常是
            // OAuth 客户端配置问题（比如 access_type 没传对），给个明确提示而不是空指针
            throw new RuntimeException("Google 未返回 refresh_token，请检查 OAuth 客户端配置（access_type=offline）");
        }

        self.persistTokens(resp.getRefreshToken(), RoleUtil.getCurrentUsername());
    }

    /** 换 token 成功后的纯 DB 写入部分，单独开短事务，见 exchangeCodeForTokens() 的说明 */
    @Transactional
    public void persistTokens(String refreshToken, String connectedByUsername) {
        authRepo.findFirstByIsDeletedFalseOrderByIdDesc().ifPresent(old -> {
            old.setIsDeleted(true);
            authRepo.save(old);
        });

        GoogleDriveAuth auth = new GoogleDriveAuth();
        auth.setIsDeleted(false);
        auth.setRefreshToken(refreshToken);
        auth.setConnectedByUsername(connectedByUsername);
        auth.setConnectedAt(new Date());
        authRepo.save(auth);
        log.info("Google Drive 授权已更新，操作人：{}", auth.getConnectedByUsername());
    }

    /** 当前是否已连接（供"账号管理"页面展示状态用） */
    public GoogleDriveAuth currentAuth() {
        return authRepo.findFirstByIsDeletedFalseOrderByIdDesc().orElse(null);
    }

    /**
     * 用存的 refresh_token 构建 Drive 客户端；没有连接过、或 refresh_token 已失效时抛异常
     * （调用方 DbBackupService 捕获后转成"需要重新连接"这类提醒，不是普通的"重试"提醒）。
     */
    public Drive buildDriveClient() throws Exception {
        GoogleDriveAuth auth = currentAuth();
        if (auth == null) {
            throw new GoogleDriveNotConnectedException("尚未连接 Google Drive 账号，请先在\"账号管理\"里连接");
        }
        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(auth.getRefreshToken())
                .build();
        // 提前触发一次刷新，让"refresh_token 已失效"这个错误在这里就抛出来，
        // 而不是拖到后面调用 Drive API 时才报一个更难辨认的错误
        try {
            credentials.refreshIfExpired();
        } catch (Exception e) {
            throw new GoogleDriveNotConnectedException(
                    "Google Drive 登录已过期，请在\"账号管理\"里重新连接：" + e.getMessage());
        }
        return new Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("Lusuoria DB Backup")
                .build();
    }

    /** 授权失效/尚未授权，需要人工去"账号管理"重新连接——DbBackupService 据此判定 authExpired */
    public static class GoogleDriveNotConnectedException extends RuntimeException {
        public GoogleDriveNotConnectedException(String message) { super(message); }
    }

    @Data
    private static class TokenResponse {
        private String access_token;
        private String refresh_token;
        private Integer expires_in;
        private String scope;
        private String token_type;

        public String getRefreshToken() { return refresh_token; }
    }
}
