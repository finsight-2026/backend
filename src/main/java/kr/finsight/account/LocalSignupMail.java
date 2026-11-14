package kr.finsight.account;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import kr.finsight.admin.AdminInvitationMail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@ConditionalOnProperty(name = "finsight.mail.mode", havingValue = "local", matchIfMissing = true)
class LocalSignupMail implements SignupMail, PasswordReset.Mail, AdminInvitationMail {
    private final Path mailbox;

    LocalSignupMail(@Value("${finsight.signup.mailbox:.local-mail}") String mailbox) {
        this.mailbox = Path.of(mailbox);
    }

    @Override
    public void deliver(String email, String code) {
        write("signup-", email, code);
    }

    @Override
    public void deliverReset(String email, String link) {
        write("password-reset-", email, link);
    }

    @Override
    public void requireAdminInvitationAvailable(String email) {
        // The local mailbox accepts the same addresses as the administrator account form.
    }

    @Override
    public void deliverAdminInvitation(String email, String link) {
        write("admin-invitation-", email, link);
    }

    @Override
    public void deliverAdminPasswordReset(String email, String link) {
        write("admin-password-reset-", email, link);
    }

    private void write(String prefix, String email, String body) {
        try {
            Files.createDirectories(mailbox, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            var message = Files.createTempFile(mailbox, ".pending-", ".txt",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(message, email + "\n" + body + "\n");
                Files.move(message, mailbox.resolve(prefix + UUID.randomUUID() + ".txt"), StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(message);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("로컬 인증 메일 저장에 실패했습니다.", error);
        }
    }
}
