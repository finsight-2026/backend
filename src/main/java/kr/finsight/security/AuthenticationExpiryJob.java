package kr.finsight.security;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
class AuthenticationExpiryJob {
    private final JdbcClient db;

    AuthenticationExpiryJob(JdbcClient db) {
        this.db = db;
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    void expire() {
        db.sql("DELETE FROM app_session WHERE expires_at<=statement_timestamp()").update();
        db.sql("DELETE FROM admin_session WHERE expires_at<=statement_timestamp()")
                .update();
        db.sql("DELETE FROM admin_session WHERE last_interaction_at<=statement_timestamp()-interval '30 minutes'")
                .update();
        db.sql("DELETE FROM admin_auth_challenge WHERE expires_at<=statement_timestamp()").update();
        db.sql("DELETE FROM app_password_reset WHERE expires_at<=statement_timestamp()").update();
        db.sql("DELETE FROM app_social_onboarding WHERE expires_at<=statement_timestamp()").update();
        db.sql("DELETE FROM app_social_authorization WHERE expires_at<=statement_timestamp()").update();
        db.sql("DELETE FROM app_password_reset_request WHERE requested_at<=statement_timestamp()-interval '1 hour'")
                .update();
        db.sql("DELETE FROM signup_email_challenge WHERE sent_at<=statement_timestamp()-interval '1 hour'").update();
        db.sql("DELETE FROM signup_email_delivery WHERE sent_at<=statement_timestamp()-interval '1 hour'").update();
        db.sql("DELETE FROM app_login_request WHERE requested_at<=statement_timestamp()-interval '15 minutes'").update();
        db.sql("DELETE FROM admin_login_request WHERE requested_at<=statement_timestamp()-interval '15 minutes'").update();
    }
}
