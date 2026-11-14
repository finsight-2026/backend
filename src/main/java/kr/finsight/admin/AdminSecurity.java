package kr.finsight.admin;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@Profile("local")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AdminSecurity {
    @Bean
    @Order(1)
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http,
            @Value("${finsight.admin.origin:https://localhost:18443}") String origin) throws Exception {
        var cookies = new CookieCsrfTokenRepository();
        cookies.setCookieName("__Secure-FINSIGHT_ADMIN_CSRF");
        cookies.setCookiePath("/api/v1/admin");
        cookies.setCookieCustomizer(cookie -> cookie.secure(true).httpOnly(true).sameSite("Strict"));
        return http.securityMatcher("/api/v1/admin/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(cookies)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                            throws ServletException, IOException {
                        var method = request.getMethod();
                        if (!"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method)
                                && !origin.equals(request.getHeader("Origin"))) {
                            response.sendError(403);
                            return;
                        }
                        chain.doFilter(request, response);
                    }
                }, CsrfFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/auth/csrf", "/api/v1/admin/auth/me").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/admins", "/api/v1/admin/auth/invitations/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/admins/invitations", "/api/v1/admin/admins/*/mfa-reset",
                                "/api/v1/admin/admins/*/resend-invitation", "/api/v1/admin/auth/invitations/*/enrollment",
                                "/api/v1/admin/auth/activate", "/api/v1/admin/auth/reset-request", "/api/v1/admin/auth/reset").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/admins/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/settings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/media/*", "/api/v1/admin/media/*/file").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/media").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/events").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/experiments", "/api/v1/admin/experiments/*/results").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/experiments", "/api/v1/admin/experiments/*/transitions").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/experiments/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/analytics/content-performance").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/settings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/users", "/api/v1/admin/users/*",
                                "/api/v1/admin/users/*/diagnoses/*", "/api/v1/admin/users/*/notes").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/users/*/pii").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/users/*/pii-grants").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/users/*/pii-grants/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/contents", "/api/v1/admin/contents/*",
                                "/api/v1/admin/contents/*/preview", "/api/v1/admin/contents/*/revisions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/contents", "/api/v1/admin/contents/*/transitions").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/contents/*/draft").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/campaigns", "/api/v1/admin/campaigns/*/deliveries").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/jobs", "/api/v1/admin/jobs/*",
                                "/api/v1/admin/deletion-requests", "/api/v1/admin/deletion-requests/*",
                                "/api/v1/admin/exports/*/file").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/exports", "/api/v1/admin/exports/*/download",
                                "/api/v1/admin/jobs/*/cancel", "/api/v1/admin/jobs/*/retry",
                                "/api/v1/admin/deletion-requests/*/retry").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/campaigns", "/api/v1/admin/campaigns/target-preview",
                                "/api/v1/admin/campaigns/from-target-preview", "/api/v1/admin/campaigns/*/preview",
                                "/api/v1/admin/campaigns/*/send", "/api/v1/admin/campaigns/*/cancel",
                                "/api/v1/admin/campaigns/*/deliveries/retry").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/campaigns/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/segments").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/segments", "/api/v1/admin/segments/*/preview",
                                "/api/v1/admin/segments/*/archive").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/segments/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/user-bulk/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/user-bulk", "/api/v1/admin/user-bulk/preview").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/tags", "/api/v1/admin/users/*/tags").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/tags").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/tags/*").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/users/*/tags").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/users/*/notes").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/users/*/notes/*").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/users/*/notes/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/auth/login", "/api/v1/admin/auth/mfa",
                                "/api/v1/admin/auth/logout", "/api/v1/admin/auth/activity", "/api/v1/admin/auth/reauth").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/comparison-snapshots",
                                "/api/v1/admin/comparison-snapshots/*", "/api/v1/admin/comparison-snapshots/*/preview").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/comparison-snapshots",
                                "/api/v1/admin/comparison-snapshots/*/transitions").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/comparison-snapshots/settings/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/audit-logs", "/api/v1/admin/dashboard", "/api/v1/admin/diagnosis-funnel").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/news", "/api/v1/admin/news/*",
                                "/api/v1/admin/news/*/revisions", "/api/v1/admin/news/editions/options",
                                "/api/v1/admin/news-editions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/news", "/api/v1/admin/news/*/*/transitions").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/news/*/*").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/news/editions/*/*", "/api/v1/admin/news-editions/*/*").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
