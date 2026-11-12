package kr.finsight.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
class AdminMutationJournal {
    record Entry(UUID id, UUID actorId, java.util.List<String> roles, String action, String targetType,
                 String targetId, String result, JsonNode changes, UUID traceId, OffsetDateTime createdAt) {}
    record Page(java.util.List<Entry> items, int page, int size, long total) {}
    record Filter(UUID actorId, String action, String targetId, String result, String reason,
                  LocalDate from, LocalDate to, int page, int size) {}
    private record Replay(String hash, String response) {}

    private final JdbcClient db;
    private final JsonMapper json;

    AdminMutationJournal(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    <T> T once(AdminAuth.Identity actor, String route, String key, Object input, Class<T> output,
               Supplier<T> operation, java.util.function.Consumer<T> audit) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key가 필요합니다.");
        }
        var actorId = actor.admin().id();
        var lock = actorId + "\n" + route + "\n" + key;
        var hash = hash(json.writeValueAsString(input));
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock, 0))")
                .param("lock", lock).query((row, index) -> true).single();
        var existing = db.sql("""
                SELECT request_hash,response FROM admin_idempotency
                WHERE actor_id = :actor AND route = :route AND request_key = :key AND expires_at > clock_timestamp()
                """).param("actor", actorId).param("route", route).param("key", key)
                .query((row, index) -> new Replay(row.getString(1), row.getString(2))).optional();
        if (existing.isPresent()) {
            if (!existing.get().hash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT);
            return json.readValue(existing.get().response(), output);
        }
        var response = operation.get();
        audit.accept(response);
        db.sql("""
                INSERT INTO admin_idempotency(actor_id,route,request_key,request_hash,response,expires_at)
                VALUES (:actor,:route,:key,:hash,CAST(:response AS jsonb),clock_timestamp() + interval '24 hours')
                ON CONFLICT (actor_id,route,request_key) DO UPDATE SET
                    request_hash = excluded.request_hash,response = excluded.response,expires_at = excluded.expires_at
                """).param("actor", actorId).param("route", route).param("key", key).param("hash", hash)
                .param("response", json.writeValueAsString(response)).update();
        return response;
    }

    @Transactional
    <T> T record(AdminAuth.Identity actor, String action, String targetType, String targetId,
                 Supplier<T> operation, java.util.function.Function<T, Map<String, ?>> changes) {
        var result = operation.get();
        append(actor, action, targetType, targetId, changes.apply(result));
        return result;
    }

    void append(AdminAuth.Identity actor, String action, String targetType, String targetId, Map<String, ?> changes) {
        append(actor.admin().id(), actor.permissions(), action, targetType, targetId, changes);
    }

    void appendServer(UUID accountId, String action, Map<String, ?> changes) {
        var metadata = new LinkedHashMap<String, Object>(changes);
        metadata.put("source", "SERVER_CONSOLE");
        append(accountId, java.util.List.of(), action, "ADMIN_ACCOUNT", accountId.toString(), metadata);
    }

    private void append(UUID actorId, java.util.List<String> roles, String action, String targetType, String targetId,
                        Map<String, ?> changes) {
        db.sql("""
                INSERT INTO audit_log (id,actor_id,roles,action,target_type,target_id,result,changes_safe,trace_id)
                VALUES (:id,:actor,ARRAY(SELECT jsonb_array_elements_text(CAST(:roles AS jsonb))),
                    :action,:targetType,:targetId,'SUCCESS',CAST(:changes AS jsonb),:trace)
                """).param("id", UUID.randomUUID()).param("trace", UUID.randomUUID()).param("actor", actorId)
                .param("roles", json.writeValueAsString(roles)).param("action", action)
                .param("targetType", targetType).param("targetId", targetId)
                .param("changes", json.writeValueAsString(changes)).update();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Page list(Filter filter) {
        var where = new ArrayList<>(java.util.List.of("created_at >= :start", "created_at < :end"));
        var params = new LinkedHashMap<String, Object>();
        params.put("start", filter.from().atStartOfDay(AdminUsers.KST).toOffsetDateTime());
        params.put("end", filter.to().plusDays(1).atStartOfDay(AdminUsers.KST).toOffsetDateTime());
        if (filter.reason() != null && !filter.reason().isBlank()) {
            where.add("strpos(lower(changes_safe->>'reason'), lower(:reason))>0");
            params.put("reason", filter.reason().strip());
        }
        for (var field : java.util.List.of("actor_id", "action", "target_id", "result")) {
            Object value = switch (field) {
                case "actor_id" -> filter.actorId();
                case "action" -> filter.action();
                case "target_id" -> filter.targetId();
                default -> filter.result();
            };
            if (value != null) {
                where.add(field + " = :" + field);
                params.put(field, value);
            }
        }
        var source = " FROM audit_log WHERE " + String.join(" AND ", where);
        var total = db.sql("SELECT count(*)" + source).params(params).query(Long.class).single();
        var items = db.sql("SELECT id,actor_id,roles,action,target_type,target_id,result,changes_safe,trace_id,created_at"
                        + source + " ORDER BY created_at DESC,id DESC LIMIT :size OFFSET :offset")
                .params(params).param("size", filter.size()).param("offset", (long)(filter.page() - 1) * filter.size())
                .query((row, index) -> new Entry(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        java.util.List.of((String[]) row.getArray(3).getArray()), row.getString(4), row.getString(5),
                        row.getString(6), row.getString(7), json.readTree(row.getString(8)),
                        row.getObject(9, UUID.class), row.getObject(10, OffsetDateTime.class))).list();
        return new Page(items, filter.page(), filter.size(), total);
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    void purgeExpiredResponses() {
        db.sql("DELETE FROM admin_idempotency WHERE expires_at <= clock_timestamp()").update();
    }

    private static String hash(String input) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
