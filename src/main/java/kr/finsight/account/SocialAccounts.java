package kr.finsight.account;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class SocialAccounts {
    enum Provider { KAKAO, APPLE }

    // Created only by a provider adapter after verifying its response on the server.
    record VerifiedIdentity(Provider provider, String subject, @Email @Size(max = 254) String verifiedEmail) {
        VerifiedIdentity {
            Objects.requireNonNull(provider);
            if (subject == null || subject.isBlank() || subject.length() > 255) {
                throw new IllegalArgumentException("Invalid provider subject");
            }
        }
    }

    record SignIn(AppSessions.Login login, String onboardingToken, String verifiedEmail) {}
    record Registration(String onboardingToken, String email, String code, Map<String, String> profile,
                        boolean terms, boolean privacy, boolean research, String documentVersion) {}
    enum Result { CREATED, LINKED, EXPIRED, INVALID_INPUT, INVALID_CODE, EXPIRED_CODE, POLICY_CHANGED,
        EXISTING_ACCOUNT, REAUTH_REQUIRED, IDENTITY_IN_USE, UNLINKED, LAST_CREDENTIAL }
    record Registered(Result result, AppSessions.Tokens tokens) {}
    private record Onboarding(Provider provider, String subject, String verifiedEmail) {}

    private final JdbcClient db;
    private final Validator validator;
    private final AppSessions sessions;
    private final SignupService signup;
    private final PolicyDocuments policies;
    private final WithdrawalService withdrawals;

    SocialAccounts(JdbcClient db, Validator validator, AppSessions sessions, SignupService signup,
                   PolicyDocuments policies, WithdrawalService withdrawals) {
        this.db = db;
        this.validator = validator;
        this.sessions = sessions;
        this.signup = signup;
        this.policies = policies;
        this.withdrawals = withdrawals;
    }

    @Transactional
    SignIn signIn(VerifiedIdentity identity) {
        lockIdentity(identity.provider(), identity.subject());
        var linkedUser = linkedUser(identity.provider(), identity.subject());
        if (linkedUser != null) return new SignIn(sessions.issueForAuthenticated(linkedUser), null, null);
        var email = validEmail(identity.verifiedEmail()) ? SignupService.normalize(identity.verifiedEmail()) : null;
        var token = sessions.newToken();
        db.sql("""
                INSERT INTO app_social_onboarding (token_hash,provider,provider_subject,verified_email,expires_at)
                VALUES (:hash,:provider,:subject,:email,clock_timestamp()+interval '10 minutes')
                """).param("hash", AppSessions.hash(token)).param("provider", identity.provider().name())
                .param("subject", identity.subject()).param("email", email, java.sql.Types.VARCHAR).update();
        return new SignIn(null, token, email);
    }

    @Transactional
    SignupService.Window sendEmail(String token, String email, String ip) {
        var onboarding = lockOnboarding(token);
        if (onboarding == null || onboarding.verifiedEmail() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return signup.send(email, ip);
    }

    @Transactional
    Registered register(Registration request) {
        var onboarding = lockOnboarding(request.onboardingToken());
        if (onboarding == null) return rejected(Result.EXPIRED);
        if (!request.terms() || !request.privacy() || request.documentVersion() == null
                || !validEmail(request.email())) return rejected(Result.INVALID_INPUT);
        var email = SignupService.normalize(request.email());
        boolean needsCode = onboarding.verifiedEmail() == null;
        if (!needsCode && !email.equals(onboarding.verifiedEmail())) return rejected(Result.INVALID_INPUT);
        if (needsCode && (request.code() == null || !request.code().matches("[0-9]{6}"))) {
            return rejected(Result.INVALID_INPUT);
        }
        Map<String, String> profile;
        try {
            profile = ProfileInput.validate(request.profile());
        } catch (IllegalArgumentException invalid) {
            return rejected(Result.INVALID_INPUT);
        }
        if (policies.activeVersion().filter(request.documentVersion()::equals).isEmpty()) {
            return rejected(Result.POLICY_CHANGED);
        }
        lockIdentity(onboarding.provider(), onboarding.subject());
        if (linkedUser(onboarding.provider(), onboarding.subject()) != null) return rejected(Result.IDENTITY_IN_USE);
        signup.lockEmail(email);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (needsCode) {
            var failure = signup.verifyCode(email, request.code(), now);
            if (failure == SignupService.Outcome.EXPIRED_CODE) return rejected(Result.EXPIRED_CODE);
            if (failure == SignupService.Outcome.INVALID_CODE) return rejected(Result.INVALID_CODE);
        }
        var user = signup.create(email, null, profile, request.research(), request.documentVersion(), "SOCIAL_SIGNUP", now);
        if (user == null) {
            if (needsCode) {
                db.sql("UPDATE app_social_onboarding SET verified_email=:email WHERE token_hash=:hash")
                        .param("email", email).param("hash", AppSessions.hash(request.onboardingToken())).update();
            }
            return rejected(Result.EXISTING_ACCOUNT);
        }
        if (!insertIdentity(user, onboarding.provider(), onboarding.subject())) {
            throw new IllegalStateException("Social identity changed under the identity lock");
        }
        consume(request.onboardingToken());
        return new Registered(Result.CREATED, sessions.issueForAuthenticated(user).tokens());
    }

    @Transactional
    Result linkSameEmail(UUID user, UUID session, String token) {
        var onboarding = lockOnboarding(token);
        if (onboarding == null) return Result.EXPIRED;
        lockIdentity(onboarding.provider(), onboarding.subject());
        var email = lockActiveAccount(user);
        if (email == null || !hasRecentAuthentication(user, session)) return Result.REAUTH_REQUIRED;
        if (!email.equals(onboarding.verifiedEmail())) return Result.INVALID_INPUT;
        if (!insertIdentity(user, onboarding.provider(), onboarding.subject())) return Result.IDENTITY_IN_USE;
        consume(token);
        return Result.LINKED;
    }

    @Transactional
    boolean reauthenticate(UUID user, UUID session, VerifiedIdentity identity) {
        lockIdentity(identity.provider(), identity.subject());
        return lockActiveAccount(user) != null
                && user.equals(linkedUser(identity.provider(), identity.subject()))
                && withdrawals.markReauthenticated(user, session);
    }

    @Transactional
    Result link(UUID user, UUID session, VerifiedIdentity identity) {
        lockIdentity(identity.provider(), identity.subject());
        if (lockActiveAccount(user) == null || !hasRecentAuthentication(user, session)) return Result.REAUTH_REQUIRED;
        var owner = linkedUser(identity.provider(), identity.subject());
        if (user.equals(owner)) return Result.LINKED;
        return insertIdentity(user, identity.provider(), identity.subject()) ? Result.LINKED : Result.IDENTITY_IN_USE;
    }

    @Transactional
    Result unlink(UUID user, UUID session, Provider provider) {
        var subject = db.sql("SELECT provider_subject FROM app_social_identity WHERE user_id=:user AND provider=:provider")
                .param("user", user).param("provider", provider.name()).query(String.class).optional().orElse(null);
        if (subject != null) lockIdentity(provider, subject);
        if (lockActiveAccount(user) == null || !hasRecentAuthentication(user, session)) return Result.REAUTH_REQUIRED;
        if (subject == null) return Result.UNLINKED;
        var hasOther = db.sql("""
                SELECT password_hash IS NOT NULL OR EXISTS (
                    SELECT 1 FROM app_social_identity WHERE user_id=:user AND provider<>:provider AND provider IN ('KAKAO','APPLE')
                ) FROM app_user WHERE id=:user
                """).param("user", user).param("provider", provider.name()).query(Boolean.class).single();
        if (!hasOther) return Result.LAST_CREDENTIAL;
        db.sql("DELETE FROM app_social_identity WHERE user_id=:user AND provider=:provider AND provider_subject=:subject")
                .param("user", user).param("provider", provider.name()).param("subject", subject).update();
        return Result.UNLINKED;
    }

    @Transactional
    boolean cancelWithdrawal(VerifiedIdentity identity) {
        lockIdentity(identity.provider(), identity.subject());
        var user = linkedUser(identity.provider(), identity.subject());
        return user != null && withdrawals.cancelVerifiedUser(user);
    }

    List<Provider> providers(UUID user) {
        return db.sql("SELECT provider FROM app_social_identity WHERE user_id=:user AND provider IN ('KAKAO','APPLE') ORDER BY provider")
                .param("user", user).query(String.class).list().stream().map(Provider::valueOf).toList();
    }

    private boolean validEmail(String email) {
        return email != null && !email.isBlank()
                && validator.validateValue(VerifiedIdentity.class, "verifiedEmail", email.strip()).isEmpty();
    }

    private UUID linkedUser(Provider provider, String subject) {
        return db.sql("SELECT user_id FROM app_social_identity WHERE provider=:provider AND provider_subject=:subject")
                .param("provider", provider.name()).param("subject", subject).query(UUID.class).optional().orElse(null);
    }

    private Onboarding lockOnboarding(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        return db.sql("""
                SELECT provider,provider_subject,verified_email FROM app_social_onboarding
                WHERE token_hash=:hash AND expires_at>clock_timestamp()
                    AND provider IN ('KAKAO','APPLE') FOR UPDATE
                """).param("hash", AppSessions.hash(token))
                .query((row, index) -> new Onboarding(Provider.valueOf(row.getString(1)), row.getString(2), row.getString(3)))
                .optional().orElse(null);
    }

    private void consume(String token) {
        db.sql("DELETE FROM app_social_onboarding WHERE token_hash=:hash")
                .param("hash", AppSessions.hash(token)).update();
    }

    private void lockIdentity(Provider provider, String subject) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "app-social:" + provider.name() + ":" + subject).query((row, index) -> true).single();
    }

    private String lockActiveAccount(UUID user) {
        return db.sql("SELECT email FROM app_user WHERE id=:user AND status='ACTIVE' FOR UPDATE")
                .param("user", user).query(String.class).optional().orElse(null);
    }

    private boolean hasRecentAuthentication(UUID user, UUID session) {
        return db.sql("""
                SELECT 1 FROM app_session WHERE user_id=:user AND id=:session
                    AND reauthenticated_at>clock_timestamp()-interval '5 minutes' AND expires_at>clock_timestamp()
                FOR UPDATE
                """).param("user", user).param("session", session).query(Integer.class).optional().isPresent();
    }

    private boolean insertIdentity(UUID user, Provider provider, String subject) {
        return db.sql("""
                INSERT INTO app_social_identity (user_id,provider,provider_subject)
                VALUES (:user,:provider,:subject) ON CONFLICT DO NOTHING
                """).param("user", user).param("provider", provider.name()).param("subject", subject).update() == 1;
    }

    private static Registered rejected(Result result) {
        return new Registered(result, null);
    }
}
