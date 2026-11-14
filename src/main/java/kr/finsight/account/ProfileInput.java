package kr.finsight.account;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProfileInput {
    private static final String OTHER = "OTHER";
    private static final Field DEBT_TYPE =
            new Field("debtType", Set.of("STUDENT", "CREDIT", "HOUSING", "CARD", "UNDISCLOSED", OTHER));
    private static final List<Field> REQUIRED_FIELDS = List.of(
            new Field("age", Set.of("19_22", "23_25", "26_29", "30_PLUS")),
            new Field("situation", Set.of("COLLEGE_EARLY", "COLLEGE_LATE", "JOB_SEEKER", "EMPLOYED", OTHER)),
            new Field("gender", Set.of("MALE", "FEMALE", "UNDISCLOSED")),
            new Field("housing", Set.of("WITH_FAMILY", "MONTHLY_RENT", "JEONSE", "OWNED", "DORMITORY", OTHER)),
            new Field("income", Set.of("REGULAR", "IRREGULAR", "SUPPORT", "NONE", OTHER)),
            new Field("debt", Set.of("YES", "NO", "UNKNOWN", "UNDISCLOSED", OTHER)),
            new Field("expenses", Set.of("MOSTLY_SUPPORTED", "PARTLY_SELF_PAID", "MOSTLY_SELF_PAID", "FULLY_SELF_PAID"))
    );

    private ProfileInput() {}

    public static Map<String, String> validate(Map<String, String> input) {
        if (input == null) {
            throw new IllegalArgumentException("프로필 입력이 필요합니다.");
        }
        var validated = new HashMap<String, String>();
        for (var field : REQUIRED_FIELDS) {
            field.validate(input, validated);
        }
        if ("YES".equals(validated.get("debt"))) {
            DEBT_TYPE.validate(input, validated);
        }
        return Map.copyOf(validated);
    }

    private record Field(String key, Set<String> codes) {
        void validate(Map<String, String> input, Map<String, String> validated) {
            var value = input.get(key);
            if (value == null || !codes.contains(value)) {
                throw new IllegalArgumentException("유효한 프로필 항목을 선택해주세요: " + key);
            }
            validated.put(key, value);
            if (OTHER.equals(value)) {
                var otherKey = key + "Other";
                var description = input.getOrDefault(otherKey, "");
                if (description == null || description.isBlank()) {
                    throw new IllegalArgumentException("기타 내용을 입력해주세요: " + key);
                }
                var normalized = description.strip();
                if (normalized.codePointCount(0, normalized.length()) > 50) {
                    throw new IllegalArgumentException("기타 내용은 50자까지 입력할 수 있습니다: " + key);
                }
                validated.put(otherKey, normalized);
            }
        }
    }
}
