package kr.finsight.admin;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminAccounts {
    enum Role { SUPER_ADMIN, OPERATOR, PRIVACY, ANALYST }
    enum Status { INVITED, ACTIVE, SUSPENDED }
    record Account(UUID id, String email, String name, List<Role> roles, Status status, long version, boolean enrolled) {}
    record Invite(String email, String name, List<Role> roles, String reason) {}
    record Change(List<Role> roles, Status status, Long expectedVersion, String reason) {}
    private record Target(Account account, boolean passwordSet) {}

    private static final String SELECT = """
            SELECT a.id,a.email,a.name,a.status,a.version,a.password_hash IS NOT NULL,
                a.totp_secret_encrypted IS NOT NULL,
                ARRAY(SELECT r.role FROM admin_role r WHERE r.admin_id=a.id ORDER BY r.role)
            FROM admin_account a
            """;

    private final JdbcClient db;
    private final AdminAuth auth;
    private final AdminInvitations invitations;
    private final AdminMutationJournal journal;

    AdminAccounts(JdbcClient db, AdminAuth auth, AdminInvitations invitations, AdminMutationJournal journal) {
        this.db = db;
        this.auth = auth;
        this.invitations = invitations;
        this.journal = journal;
    }

    List<Account> list(AdminAuth.Identity actor) {
        requireSupervisor(actor);
        return db.sql(SELECT + " ORDER BY a.name,a.id").query(this::target).list().stream().map(Target::account).toList();
    }

    @Transactional
    AdminInvitations.Receipt invite(AdminAuth.Identity actor, String session, Invite request) {
        begin(actor, session);
        var reason = reason(request.reason());
        var roles = roles(request.roles());
        var email = request.email() == null ? "" : AdminAuth.normalize(request.email());
        var name = request.name() == null ? "" : request.name().strip();
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.length() > 254
                || name.isEmpty() || name.codePointCount(0, name.length()) > 50) throw invalid();
        var id = UUID.randomUUID();
        int inserted = db.sql("""
                INSERT INTO admin_account(id,email,name,status) VALUES (:id,:email,:name,'INVITED')
                ON CONFLICT(email) DO NOTHING
                """).param("id", id).param("email", email).param("name", name).update();
        if (inserted == 0) throw conflict();
        for (var role : roles) addRole(id, role);
        var result = invitations.issue(id, email, AdminInvitations.Purpose.INVITATION);
        journal.append(actor, "ADMIN_INVITED", "ADMIN_ACCOUNT", id.toString(), Map.of("roles", roles, "reason", reason));
        return result;
    }

    @Transactional
    Account update(AdminAuth.Identity actor, String session, UUID id, Change request) {
        begin(actor, session);
        var reason = reason(request.reason());
        var current = lock(id);
        var before = current.account();
        if (request.expectedVersion() == null) throw invalid();
        if (before.version() != request.expectedVersion()) throw conflict();
        var nextRoles = request.roles() == null ? before.roles() : roles(request.roles());
        var status = request.status() == null ? before.status() : request.status();
        if ((status == Status.ACTIVE && !before.enrolled()) || (status == Status.INVITED && before.enrolled())) throw invalid();
        if (status == before.status() && nextRoles.equals(before.roles())) return before;
        protectLastSupervisor(before, status, nextRoles);
        for (var role : before.roles()) {
            if (!nextRoles.contains(role)) db.sql("DELETE FROM admin_role WHERE admin_id=:id AND role=:role")
                    .param("id", id).param("role", role.name()).update();
        }
        for (var role : nextRoles) if (!before.roles().contains(role)) addRole(id, role);
        changeStatus(id, status);
        if (status != Status.INVITED) invitations.revoke(id);
        invalidateSessions(id);
        journal.append(actor, "ADMIN_ACCOUNT_UPDATED", "ADMIN_ACCOUNT", id.toString(),
                Map.of("reason", reason, "beforeStatus", before.status(), "status", status,
                        "beforeRoles", before.roles(), "roles", nextRoles, "version", before.version()+1));
        return lock(id).account();
    }

    @Transactional
    AdminInvitations.Receipt resend(AdminAuth.Identity actor, String session, UUID id, String input) {
        begin(actor, session);
        var reason = reason(input);
        var target = lock(id);
        if (target.account().status() != Status.INVITED) throw conflict();
        var result = invitations.issue(id, target.account().email(),
                target.passwordSet() ? AdminInvitations.Purpose.MFA_RESET : AdminInvitations.Purpose.INVITATION);
        journal.append(actor, "ADMIN_INVITATION_RESENT", "ADMIN_ACCOUNT", id.toString(), Map.of("reason", reason));
        return result;
    }

    @Transactional
    AdminInvitations.Receipt resetMfa(AdminAuth.Identity actor, String session, UUID id, String input) {
        begin(actor, session);
        var reason = reason(input);
        var target = lock(id).account();
        if (actor.admin().id().equals(id) || target.status() != Status.ACTIVE) throw conflict();
        protectLastSupervisor(target, Status.INVITED, target.roles());
        var result = issueMfaReset(target);
        journal.append(actor, "ADMIN_MFA_RESET", "ADMIN_ACCOUNT", id.toString(), Map.of("reason", reason));
        return result;
    }

    @Transactional
    AdminInvitations.Receipt recoverLastSupervisor(UUID id, String input) {
        var reason = reason(input);
        invitations.lockLifecycle();
        var target = lock(id);
        if (!target.account().roles().contains(Role.SUPER_ADMIN) || !target.passwordSet()
                || otherActiveSupervisor(id)) throw conflict();
        var account = target.account();
        if (account.status() == Status.ACTIVE) {
            if (!account.enrolled()) throw conflict();
            var result = issueMfaReset(account);
            journal.appendServer(id, "ADMIN_SERVER_MFA_RECOVERY", Map.of("reason", reason));
            return result;
        }
        if (account.status() != Status.INVITED || !db.sql("""
                SELECT coalesce((
                    SELECT action='ADMIN_SERVER_MFA_RECOVERY' FROM audit_log
                    WHERE target_type='ADMIN_ACCOUNT' AND target_id=:id
                        AND action IN ('ADMIN_SERVER_MFA_RECOVERY','ADMIN_MFA_REENROLLED')
                    ORDER BY created_at DESC,id DESC LIMIT 1
                ),false)
                """).param("id", id.toString()).query(Boolean.class).single()) throw conflict();
        var result = invitations.issue(id, account.email(), AdminInvitations.Purpose.MFA_RESET);
        journal.appendServer(id, "ADMIN_SERVER_MFA_RECOVERY", Map.of("reason", reason, "reissued", true));
        return result;
    }

    private AdminInvitations.Receipt issueMfaReset(Account target) {
        var id = target.id();
        db.sql("""
                UPDATE admin_account SET status='INVITED',totp_secret_encrypted=NULL,last_totp_step=-1,
                    mfa_reauth_failures=0,mfa_blocked_until=NULL,session_version=session_version+1,version=version+1
                WHERE id=:id
                """).param("id", id).update();
        invalidateSessions(id);
        db.sql("DELETE FROM admin_recovery_code WHERE admin_id=:id").param("id", id).update();
        return invitations.issue(id, target.email(), AdminInvitations.Purpose.MFA_RESET);
    }

    private void protectLastSupervisor(Account current, Status status, List<Role> roles) {
        if (current.status() != Status.ACTIVE || !current.roles().contains(Role.SUPER_ADMIN)
                || (status == Status.ACTIVE && roles.contains(Role.SUPER_ADMIN))) return;
        if (!otherActiveSupervisor(current.id())) throw conflict();
    }

    private boolean otherActiveSupervisor(UUID id) {
        return db.sql("""
                SELECT EXISTS(SELECT 1 FROM admin_account a JOIN admin_role r ON r.admin_id=a.id
                    WHERE a.id<>:id AND a.status='ACTIVE' AND r.role='SUPER_ADMIN')
                """).param("id", id).query(Boolean.class).single();
    }

    private void begin(AdminAuth.Identity actor, String session) {
        requireSupervisor(actor);
        invitations.lockLifecycle();
        auth.lockVerifiedSupervisor(actor, session);
    }

    private void changeStatus(UUID id, Status status) {
        db.sql("""
                UPDATE admin_account SET status=:status,session_version=session_version+1,version=version+1 WHERE id=:id
                """).param("status", status.name()).param("id", id).update();
    }

    private void invalidateSessions(UUID id) {
        db.sql("DELETE FROM admin_session WHERE admin_id=:id").param("id", id).update();
        db.sql("DELETE FROM admin_auth_challenge WHERE admin_id=:id").param("id", id).update();
        db.sql("DELETE FROM admin_password_reset WHERE admin_id=:id").param("id", id).update();
    }

    private void addRole(UUID id, Role role) {
        db.sql("INSERT INTO admin_role(admin_id,role) VALUES (:id,:role)").param("id", id).param("role", role.name()).update();
    }

    private Target lock(UUID id) {
        return db.sql(SELECT + " WHERE a.id=:id FOR UPDATE").param("id", id).query(this::target).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Target target(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        boolean password = row.getBoolean(6);
        var roles = Arrays.stream((String[]) row.getArray(8).getArray()).map(Role::valueOf).toList();
        return new Target(new Account(row.getObject(1, UUID.class), row.getString(2), row.getString(3), roles,
                Status.valueOf(row.getString(4)), row.getLong(5), password && row.getBoolean(7)), password);
    }

    private static List<Role> roles(List<Role> roles) {
        if (roles == null || roles.isEmpty() || roles.size() > Role.values().length || roles.stream().anyMatch(java.util.Objects::isNull)
                || roles.stream().distinct().count() != roles.size()) throw invalid();
        return roles.stream().sorted(java.util.Comparator.comparing(Enum::name)).toList();
    }

    private static String reason(String input) {
        var reason = input == null ? "" : input.strip();
        int length = reason.codePointCount(0, reason.length());
        if (length < 10 || length > 500) throw invalid();
        return reason;
    }

    private static void requireSupervisor(AdminAuth.Identity actor) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!actor.permissions().contains("SUPER_ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
