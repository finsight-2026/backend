package kr.finsight.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

final class PendingDataDeletionFilter extends OncePerRequestFilter {
    private final JdbcClient db;

    PendingDataDeletionFilter(JdbcClient db) { this.db = db; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/v1/") && !allowedWhilePending(request.getMethod(), path)
                && SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
                && db.sql("SELECT EXISTS(SELECT 1 FROM app_user WHERE id=:id AND status='DATA_DELETION_PENDING')")
                    .param("id", UUID.fromString(jwt.getToken().getSubject())).query(Boolean.class).single()) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"DATA_DELETION_PENDING\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean allowedWhilePending(String method, String path) {
        return path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/admin/")
                || path.equals("/api/v1/account/settings") || path.startsWith("/api/v1/account/settings/")
                || path.equals("/api/v1/account/data-deletion") || path.startsWith("/api/v1/account/data-deletion/")
                || path.equals("/api/v1/comparison")
                || (method.equals("GET") && (path.equals("/api/v1/diagnosis/attempts")
                    || path.startsWith("/api/v1/diagnosis/attempts/") || path.equals("/api/v1/diagnosis/monthly-report")
                    || path.equals("/api/v1/account/export") || path.startsWith("/api/v1/account/export/")));
    }
}
