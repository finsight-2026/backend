package kr.finsight.admin;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local")
class AdminBootstrap {
    @Bean
    @ConditionalOnProperty(name = "finsight.admin.bootstrap", havingValue = "true")
    ApplicationRunner initializeFirstAdmin(AdminAuth auth,
            @Value("${FINSIGHT_BOOTSTRAP_ADMIN_EMAIL}") String email,
            @Value("${FINSIGHT_BOOTSTRAP_ADMIN_PASSWORD}") String password,
            @Value("${FINSIGHT_BOOTSTRAP_ADMIN_TOTP}") String seed) {
        return args -> auth.bootstrap(email.strip(), password, seed);
    }

    @Bean
    @ConditionalOnNotWebApplication
    @ConditionalOnProperty(name = "finsight.admin.recovery.command", havingValue = "true")
    ApplicationRunner recoverLastAdmin(AdminAccounts accounts,
            @Value("${FINSIGHT_ADMIN_RECOVERY_ID}") UUID id,
            @Value("${FINSIGHT_ADMIN_RECOVERY_REASON}") String reason) {
        return args -> {
            var receipt = accounts.recoverLastSupervisor(id, reason);
            LoggerFactory.getLogger(AdminBootstrap.class).info(
                    "최고관리자 MFA 복구 접수 {} 만료 {}", receipt.adminId(), receipt.expiresAt());
        };
    }
}
