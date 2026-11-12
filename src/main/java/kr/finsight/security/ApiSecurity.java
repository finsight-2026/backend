package kr.finsight.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ApiSecurity {
    @Bean
    SecurityFilterChain finsightSecurityFilterChain(HttpSecurity http, ObjectProvider<JwtDecoder> decoders, JdbcClient db)
            throws Exception {
        var publicPosts = new String[]{
                "/api/v1/auth/signup/code", "/api/v1/auth/signup/verify", "/api/v1/auth/login", "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/complete", "/api/v1/auth/refresh", "/api/v1/auth/logout/revoke", "/api/v1/account/withdrawal/cancel",
                "/api/v1/auth/social/start", "/api/v1/auth/social/finish", "/api/v1/auth/social/email",
                "/api/v1/auth/social/register", "/api/v1/auth/social/callback/apple"};
        var socialAccountPosts = new String[]{"/api/v1/account/social/start", "/api/v1/account/social/finish",
                "/api/v1/account/social/link", "/api/v1/account/social/*"};
        var logout = "/api/v1/auth/logout";
        var pushDevice = "/api/v1/notifications/device";
        var decoder = decoders.getIfAvailable();
        http.cors(Customizer.withDefaults()).csrf(csrf -> csrf.ignoringRequestMatchers(publicPosts)
                        .ignoringRequestMatchers(socialAccountPosts)
                        .ignoringRequestMatchers(logout, pushDevice, "/api/v1/account/activity", "/api/v1/notifications/preferences", "/api/v1/notifications/*/read", "/api/v1/account/settings/profile", "/api/v1/account/settings/consents/research", "/api/v1/guides/*/view", "/api/v1/guides/*/events", "/api/v1/news/*/events", "/api/v1/guides/*/completion", "/api/v1/account/reauth", "/api/v1/account/withdrawal", "/api/v1/account/data-deletion", "/api/v1/account/export", "/api/v1/account/export/*/link", "/api/v1/diagnosis/attempts", "/api/v1/diagnosis/attempts/**", "/api/v1/experiments/assign", "/api/v1/experiments/*/exposure", "/api/v1/insights/*/helpful", "/api/v1/insights/*/events"))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (decoder != null) {
            http.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder)));
            http.addFilterAfter(new PendingDataDeletionFilter(db), BearerTokenAuthenticationFilter.class);
        }
        return http.authorizeHttpRequests(requests -> {
            requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/v1/surveys/current",
                            "/api/v1/policies", "/api/v1/policies/*/*",
                            "/.well-known/apple-app-site-association").permitAll();
            if (decoder != null) {
                requests.requestMatchers(HttpMethod.POST, publicPosts).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/social/providers",
                                "/api/v1/auth/social/callback/naver", "/api/v1/auth/social/callback/kakao").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/account/social/start", "/api/v1/account/social/finish",
                                "/api/v1/account/social/link").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/account/social/*").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/notifications", "/api/v1/notifications/preferences", "/api/v1/notifications/*/target").authenticated()
                        .requestMatchers(HttpMethod.PUT, pushDevice).authenticated()
                        .requestMatchers(HttpMethod.DELETE, pushDevice).authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/notifications/preferences", "/api/v1/notifications/*/read").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/account/settings", "/api/v1/account/settings/consents").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/account/data-deletion", "/api/v1/account/data-deletion/*").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/account/settings/profile", "/api/v1/account/settings/consents/research").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/account/export/download/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/account/export", "/api/v1/account/export/*", "/api/v1/comparison", "/api/v1/guides", "/api/v1/guides/widget", "/api/v1/diagnosis/monthly-report", "/api/v1/news", "/api/v1/news/*", "/api/v1/news/*/image", "/api/v1/insights", "/api/v1/insights/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/experiments/assign", "/api/v1/experiments/*/exposure").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/guides/*/view", "/api/v1/guides/*/events", "/api/v1/news/*/events", "/api/v1/insights/*/events").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/guides/*/completion", "/api/v1/insights/*/helpful").authenticated()
                        .requestMatchers(HttpMethod.POST, logout, "/api/v1/account/activity", "/api/v1/account/reauth", "/api/v1/account/withdrawal", "/api/v1/account/data-deletion", "/api/v1/account/export", "/api/v1/account/export/*/link", "/api/v1/diagnosis/attempts",
                                "/api/v1/diagnosis/attempts/*/submit", "/api/v1/diagnosis/attempts/*/abort", "/api/v1/diagnosis/attempts/*/views", "/api/v1/diagnosis/attempts/journeys",
                                "/api/v1/diagnosis/attempts/*/milestones", "/api/v1/diagnosis/attempts/*/question-events").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/diagnosis/attempts", "/api/v1/diagnosis/attempts/trend",
                                "/api/v1/diagnosis/attempts/widget",
                                "/api/v1/diagnosis/attempts/*/result").authenticated();
            }
            requests.anyRequest().denyAll();
        }).build();
    }
}
