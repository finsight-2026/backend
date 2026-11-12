package kr.finsight.account;

import java.net.URI;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class SocialAuthorization {
    enum Purpose { LOGIN, LINK, REAUTH, CANCEL_WITHDRAWAL }
    private enum Status { AUTHORIZED, CANCELED, FAILED }
    record Session(UUID user, UUID session) {}
    record Started(URI authorizationUrl, String state, String verifier) {}
    record Verified(Purpose purpose, SocialAccounts.VerifiedIdentity identity, boolean canceled) {}
    private record Completed(Purpose purpose, Status status, SocialAccounts.Provider provider, String subject, String email) {}

    private final JdbcClient db;
    private final AppSessions tokens;
    private final SocialProviderGateway gateway;

    SocialAuthorization(JdbcClient db, AppSessions tokens, SocialProviderGateway gateway) {
        this.db = db;
        this.tokens = tokens;
        this.gateway = gateway;
    }

    List<SocialAccounts.Provider> providers() {
        return Arrays.stream(SocialAccounts.Provider.values()).filter(gateway::configured).toList();
    }

    @Transactional
    Started start(SocialAccounts.Provider provider, Purpose purpose, Session session, String ip) {
        if (!gateway.configured(provider)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "app-social-ip:" + ip).query((row, index) -> true).single();
        int recent = db.sql("""
                SELECT count(*) FROM app_social_authorization
                WHERE source_ip=:ip AND expires_at>clock_timestamp()
                """).param("ip", ip).query(Integer.class).single();
        if (recent >= 30) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);

        var state = tokens.newToken();
        var verifier = tokens.newToken();
        db.sql("""
                INSERT INTO app_social_authorization (state_hash,verifier_hash,provider,purpose,source_ip,user_id,session_id,expires_at)
                VALUES (:state,:verifier,:provider,:purpose,:ip,:user,:session,clock_timestamp()+interval '10 minutes')
                """).param("state", AppSessions.hash(state)).param("verifier", AppSessions.hash(verifier))
                .param("provider", provider.name()).param("purpose", purpose.name()).param("ip", ip)
                .param("user", session == null ? null : session.user(), Types.OTHER)
                .param("session", session == null ? null : session.session(), Types.OTHER).update();
        return new Started(gateway.authorizationUrl(provider, state,
                purpose == Purpose.REAUTH || purpose == Purpose.CANCEL_WITHDRAWAL), state, verifier);
    }

    URI callback(SocialAccounts.Provider provider, String state, String code, String error) {
        if (!validToken(state) || !gateway.configured(provider)) throw notFound();
        var hash = AppSessions.hash(state);
        var claimed = db.sql("""
                UPDATE app_social_authorization SET status='EXCHANGING'
                WHERE state_hash=:hash AND provider=:provider AND status='WAITING' AND expires_at>clock_timestamp()
                """).param("hash", hash).param("provider", provider.name()).update();
        if (claimed != 1) throw notFound();

        if (error != null) {
            complete(hash, Status.CANCELED, null);
        } else if (code == null || code.isBlank() || code.length() > 4096) {
            complete(hash, Status.FAILED, null);
        } else {
            try {
                var identity = gateway.exchange(provider, state, code);
                if (identity.provider() != provider) throw new IllegalStateException("Mismatched social provider");
                complete(hash, Status.AUTHORIZED, identity);
            } catch (ResponseStatusException rejected) {
                complete(hash, Status.FAILED, null);
            }
        }
        return URI.create("finsight-social://auth/complete?state=" + state);
    }

    @Transactional
    Verified take(String state, String verifier, Session session) {
        if (!validToken(state) || !validToken(verifier)) throw notFound();
        var hash = AppSessions.hash(state);
        var completed = db.sql("""
                SELECT purpose,status,provider,provider_subject,verified_email FROM app_social_authorization
                WHERE state_hash=:state AND verifier_hash=:verifier AND expires_at>clock_timestamp()
                    AND status IN ('AUTHORIZED','FAILED','CANCELED')
                    AND user_id IS NOT DISTINCT FROM :user AND session_id IS NOT DISTINCT FROM :session FOR UPDATE
                """).param("state", hash).param("verifier", AppSessions.hash(verifier))
                .param("user", session == null ? null : session.user(), Types.OTHER)
                .param("session", session == null ? null : session.session(), Types.OTHER)
                .query((row, index) -> new Completed(Purpose.valueOf(row.getString(1)), Status.valueOf(row.getString(2)),
                        SocialAccounts.Provider.valueOf(row.getString(3)), row.getString(4), row.getString(5)))
                .optional().orElseThrow(SocialAuthorization::notFound);
        db.sql("""
                UPDATE app_social_authorization SET status='USED',provider_subject=NULL,verified_email=NULL
                WHERE state_hash=:hash
                """).param("hash", hash).update();
        var identity = completed.status() == Status.AUTHORIZED
                ? new SocialAccounts.VerifiedIdentity(completed.provider(), completed.subject(), completed.email()) : null;
        return new Verified(completed.purpose(), identity, completed.status() == Status.CANCELED);
    }

    private void complete(String stateHash, Status status, SocialAccounts.VerifiedIdentity identity) {
        int changed = db.sql("""
                UPDATE app_social_authorization SET status=:status,provider_subject=:subject,verified_email=:email
                WHERE state_hash=:hash AND status='EXCHANGING' AND expires_at>clock_timestamp()
                """).param("hash", stateHash).param("status", status.name())
                .param("subject", identity == null ? null : identity.subject(), Types.VARCHAR)
                .param("email", identity == null ? null : identity.verifiedEmail(), Types.VARCHAR).update();
        if (changed != 1) throw notFound();
    }

    private static boolean validToken(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
