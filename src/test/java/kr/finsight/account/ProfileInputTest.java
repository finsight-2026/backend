package kr.finsight.account;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileInputTest {
    private final Map<String, String> input = new HashMap<>(Map.of(
            "age", "19_22", "situation", "EMPLOYED", "gender", "UNDISCLOSED", "housing", "WITH_FAMILY",
            "income", "REGULAR", "debt", "NO", "expenses", "FULLY_SELF_PAID"));

    @Test
    void stripsDebtFieldsWhenDebtIsAbsent() {
        input.put("debtType", "OTHER");
        input.put("debtTypeOther", "이전 입력");
        var result = ProfileInput.validate(input);
        assertFalse(result.containsKey("debtType"));
        assertFalse(result.containsKey("debtTypeOther"));
        assertEquals("UNDISCLOSED", result.get("gender"));
    }

    @Test
    void debtTypeAndOtherDetailsAreConditionallyRequired() {
        input.put("debt", "YES");
        assertThrows(IllegalArgumentException.class, () -> ProfileInput.validate(input));
        input.put("debtType", "OTHER");
        input.put("debtTypeOther", "  ");
        assertThrows(IllegalArgumentException.class, () -> ProfileInput.validate(input));
        input.put("debtTypeOther", "가".repeat(51));
        assertThrows(IllegalArgumentException.class, () -> ProfileInput.validate(input));
        input.put("debtTypeOther", "  기타 대출  ");
        assertEquals("기타 대출", ProfileInput.validate(input).get("debtTypeOther"));
    }

    @Test
    void blocksUnderAgeAndMissingRequiredValues() {
        input.put("age", "UNDER_19");
        assertThrows(IllegalArgumentException.class, () -> ProfileInput.validate(input));
        input.put("age", "30_PLUS");
        input.remove("expenses");
        assertThrows(IllegalArgumentException.class, () -> ProfileInput.validate(input));
    }
}
