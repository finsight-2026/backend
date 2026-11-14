package kr.finsight.admin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AdminPasswordPolicyTest {
    @Test
    void excludesPaddingAndRejectsMalformedRangeResponses() {
        var suffix = "A".repeat(35);
        assertFalse(AdminPasswordPolicy.exposedSuffix(suffix, suffix + ":0\r\n" + "B".repeat(35) + ":42\r\n"));
        assertTrue(AdminPasswordPolicy.exposedSuffix(suffix, suffix.toLowerCase(java.util.Locale.ROOT) + ":1024\n"));
        var error = assertThrows(ResponseStatusException.class,
                () -> AdminPasswordPolicy.exposedSuffix(suffix, suffix + ":1\ninvalid"));
        assertEquals(503, error.getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> AdminPasswordPolicy.exposedSuffix(suffix, ""));
    }

    @Test
    void localScreeningRejectsCommonPasswordsWithoutNetwork() {
        var policy = new AdminPasswordPolicy(false);
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> policy.verifyNew("Password1234")).getStatusCode().value());
        assertDoesNotThrow(() -> policy.verifyNew("Local-QA-Basil-Winter-754!"));
    }
}
