package com.lusuoria.settlement.config;

import com.lusuoria.settlement.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain; // 2.7.x 依旧使用 javax
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 【Spring 知识点】
 * @Configuration：告诉 Spring 这个类里的 @Bean 方法产出的对象要交给容器管理（本质是给这个类生成一个
 * CGLIB 代理，保证同一个 @Bean 方法被别处多次调用时返回的还是同一个单例，而不是每次 new 一个新的）。
 * @EnableWebSecurity：打开 Spring Security 的 Web 安全过滤器链支持，没有这个注解，下面的
 * filterChain() 这个 Bean 不会被真正接到 Servlet 容器的请求处理流程里。
 * @EnableGlobalMethodSecurity(prePostEnabled = true)：允许在 Controller/Service 方法上直接用
 * @PreAuthorize("...") / @PostAuthorize("...") 这类注解做方法级权限校验（本项目实际上主要靠
 * RoleUtil/EmployeeRoleUtil 手写 if 判断，没怎么用这个注解风格，但类上留着这个开关）。
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true) // 2.7.x 保持使用该注解
public class SecurityConfig {

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Autowired private JwtUtil jwtUtil;
    @Autowired private JwtAuthEntryPoint jwtAuthEntryPoint;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * 【Spring 知识点】这个方法本身就是一个 @Bean：返回值 SecurityFilterChain 会被 Spring Security
     * 自动识别并接管，不需要在别处手动注册。方法体里 http.xxx().yyy().and() 是"Fluent Builder"
     * 写法——每个方法调用完返回的还是同一个配置对象（或者它的某个子配置器），可以一路链式点下去；
     * .and() 的作用是"从当前这一小段子配置（比如 cors()/sessionManagement()）跳回上一层的
     * HttpSecurity 主对象"，这样才能接着配下一段。整条链最终描述的是"这个应用的每一个 HTTP 请求
     * 要经过哪些安全检查"，等价于以前 XML 时代的 <http> 配置，只是用 Java 代码写。
     *
     * .csrf().disable()：CSRF（跨站请求伪造）防护是给"基于 Cookie/Session 记住登录状态"的传统
     * Web 应用设计的；这个系统是纯前后端分离 + JWT（每次请求都带 Authorization 头，不依赖 Cookie
     * 自动携带凭证），天然不受 CSRF 攻击影响，所以关掉，否则 POST/PUT/DELETE 请求会被无谓拦截。
     *
     * .sessionManagement().sessionCreationPolicy(STATELESS)：告诉 Spring Security 不要创建/使用
     * HttpSession 来记录"这个人登录了没有"——每次请求都必须靠请求本身带的 JWT 重新证明身份
     * （见下面的 jwtAuthFilter），服务端不保留任何会话状态，这也是"无状态"这个词的含义，方便以后
     * 水平扩容多个实例时不用做 Session 共享。
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // 针对 2.7.x 优化的配置写法，避免过多的 .and() 嵌套
        http
                .cors().configurationSource(corsConfigurationSource()).and()
                .csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .exceptionHandling().authenticationEntryPoint(jwtAuthEntryPoint).and()
                .authorizeRequests()
                // Google OAuth 回调（数据库备份用）：Google 直接跳转浏览器过来，不带我们系统的
                // JWT，只能靠 state 参数一次性校验，见 GoogleDriveAuthController/GoogleDriveAuthService
                .antMatchers("/api/auth/**", "/actuator/health", "/api/google-drive-auth/callback").permitAll()
                .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated();

        // 将 JWT 过滤器置于用户名密码认证过滤器之前——Spring Security 底层是一条"过滤器链"
        // （FilterChain），每个请求依次穿过链上的一个个 Filter，addFilterBefore 就是把
        // jwtAuthFilter() 这个自定义过滤器插到 UsernamePasswordAuthenticationFilter（Spring
        // Security 内置的、处理表单登录用的过滤器）前面。必须放前面，是因为要让"从 JWT 里解析出
        // 登录身份、塞进 SecurityContext"这一步，先于后面 authorizeRequests() 做权限判断发生——
        // 顺序反了的话，权限校验那一步执行时 SecurityContext 里还是空的，会被当成未登录直接拦截。
        http.addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        String[] origins = allowedOrigins.split(",");
        config.setAllowedOrigins(Arrays.asList(origins));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // 显式指定 Header，防止使用 "*" 配合 AllowCredentials(true) 在某些浏览器/容器中报错
        config.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With", "Accept"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * 【Spring 知识点】OncePerRequestFilter 是 Spring 提供的一个基类，保证 doFilterInternal()
     * 这个方法对同一个请求在同一次转发链路里只会被执行一次（Servlet 规范里请求可能会经过
     * forward/include 被"重新过一遍"过滤器链，直接实现 javax.servlet.Filter 接口的话就要自己去重）。
     * 这里没有单独写一个 .java 文件定义类，而是直接 new OncePerRequestFilter() { ... } 写一个
     * 匿名内部类——效果上和写一个独立的 JwtAuthFilter implements ... 类完全一样，只是图省事直接
     * 内联在这个 @Bean 方法里。这个匿名类的实例本身就是要注册进过滤器链的那个 Bean（返回值），
     * Spring 并不关心它有没有名字、是不是匿名类，只关心类型对不对。
     *
     * 每个请求实际会经历："有没有 Authorization 头 → 是不是 Bearer 开头 → token 本身有效吗
     * （没过期、签名对得上）" 三层判断，任何一层没过，SecurityContext 里就不会写入登录信息，直接
     * 放行给下一个过滤器——不在这里直接返回 401，是因为这个过滤器本身不负责"要不要求登录"这件事
     * （那是权限相关的判断），它只负责"如果带了有效凭证，就把身份信息解析出来"；真正因为没登录/
     * 权限不够而被拦截，是后面 authorizeRequests() 这层配置的职责，触发时会走到上面注入的
     * jwtAuthEntryPoint 返回 401。
     */
    @Bean
    public OncePerRequestFilter jwtAuthFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                String header = request.getHeader("Authorization");
                if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
                    String token = header.substring(7);
                    if (jwtUtil.validateToken(token)) {
                        String username = jwtUtil.getUsernameFromToken(token);

                        // 【核心优化】：不要在这里调用 userDetailsService.loadUserByUsername(username) 查库！
                        // 应当直接从 Token 的 Claims 中解析出权限列表
                        List<GrantedAuthority> authorities = jwtUtil.getAuthoritiesFromToken(token);

                        // 直接构建认证令牌，放入安全上下文中。SecurityContextHolder 默认基于
                        // ThreadLocal 实现——每个请求由 Tomcat 线程池里的某一个线程处理，写进去的
                        // 认证信息只在"当前处理这个请求的这个线程"内可见，天然不会跟其他并发请求
                        // 串号；但也正因为是 ThreadLocal，如果业务代码自己另开线程/线程池去处理
                        // 一部分逻辑（比如下面 AsyncConfig 那个 Excel 导入线程池），新线程里
                        // SecurityContextHolder 默认是空的，读不到这里设置的登录信息——这就是
                        // CollaborationTrackingExcelHandler.importDataAsync() 那段注释里反复强调
                        // "必须在派发异步任务之前、在当前请求线程里先把权限判断结果算好传进去"的根本原因。
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(username, null, authorities);

                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
                // 无论 Token 是否有效，都放行给后面的过滤器（如果没有凭证，后面的 authorizeRequests 会拦住并返回 403）
                filterChain.doFilter(request, response);
            }
        };
    }
}