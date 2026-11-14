package kr.finsight.account;

import kr.finsight.admin.AdminInvitationMail;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("local")
@ConditionalOnProperty(name = "finsight.mail.mode", havingValue = "disabled")
class DisabledOutboundMail implements SignupMail, PasswordReset.Mail, AdminInvitationMail {
    @Override
    public void requireAvailable() {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "현재 인증 메일을 보낼 수 없어요.");
    }

    @Override
    public void deliver(String email, String code) {
        requireAvailable();
    }

    @Override
    public void deliverReset(String email, String link) {
        requireAvailable();
    }

    @Override
    public void requireAdminInvitationAvailable(String email) {
        requireAvailable();
    }

    @Override
    public void deliverAdminInvitation(String email, String link) {
        requireAvailable();
    }

    @Override
    public void deliverAdminPasswordReset(String email, String link) {
        requireAvailable();
    }
}
