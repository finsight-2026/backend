package kr.finsight.notification;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

interface PushProvider {
    enum Outcome { SIMULATED, ACCEPTED, RETRY, UNKNOWN, INVALID, REJECTED, EXPIRED }

    record Result(Outcome outcome, OffsetDateTime notBefore) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }

        static Result retry(OffsetDateTime notBefore) {
            return new Result(Outcome.RETRY, notBefore);
        }
    }

    record Message(UUID notificationId, String templateId, String templateVersion,
                   String destinationType, String resourceId, String title, String body,
                   OffsetDateTime expiresAt, boolean silent) {}

    PushRegistrations.Provider provider();

    Result send(String token, Message message);
}

@Component
@Profile("local")
class LocalPushProvider implements PushProvider {
    @Override
    public PushRegistrations.Provider provider() {
        return PushRegistrations.Provider.LOCAL;
    }

    @Override
    public Result send(String token, Message message) {
        return Result.of(Outcome.SIMULATED);
    }
}
