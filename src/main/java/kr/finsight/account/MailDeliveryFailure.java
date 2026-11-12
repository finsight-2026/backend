package kr.finsight.account;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class MailDeliveryFailure extends ResponseStatusException {
    MailDeliveryFailure() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "현재 인증 메일을 보낼 수 없어요.");
    }
}
