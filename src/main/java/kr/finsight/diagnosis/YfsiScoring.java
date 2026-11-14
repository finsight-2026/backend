package kr.finsight.diagnosis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class YfsiScoring {
    public static final String FORMULA_VERSION = "yfsi-v1";
    public static final int QUESTION_COUNT = 30;

    private static final Set<Integer> RISK_REVERSED = Set.of(3, 5, 9, 10, 14, 18, 20, 21, 23, 24, 26);
    private static final Set<Integer> RESOURCE_REVERSED = Set.of(19, 22, 25);

    // Declaration order is the tie-break order from the specification.
    public enum Pressure {
        DEBT("부채부담형", 1, 3),
        INCOME("소득불안형", 4, 6),
        EMERGENCY_FUND("비상자금취약형", 7, 9),
        FIXED_COST("고정비압박형", 10, 12),
        FUTURE("미래불안형", 13, 15),
        FAMILY("부양부담형", 16, 17);

        public final String badge;
        private final int first;
        private final int last;

        Pressure(String badge, int first, int last) {
            this.badge = badge;
            this.first = first;
            this.last = last;
        }
    }

    public enum Resource {
        FINANCIAL_LITERACY, MONEY_MANAGEMENT, SELF_EFFICACY
    }

    public record Score(int numerator, int denominator) implements Comparable<Score> {
        public int display() {
            return (2 * numerator + denominator) / (2 * denominator);
        }

        @Override
        public int compareTo(Score other) {
            return Long.compare((long) numerator * other.denominator, (long) other.numerator * denominator);
        }
    }

    public record PressureScore(Pressure pressure, Score score) {}
    public record ResourceScore(Resource resource, Score score) {}

    public record Result(
            String formulaVersion,
            Score total,
            Pressure representativeType,
            List<PressureScore> rankedPressures,
            List<ResourceScore> resources,
            Score perceivedStress,
            String overallStress
    ) {}

    private static final List<String> OVERALL_STRESS = List.of("매우 낮음", "낮음", "보통", "높음", "매우 높음");

    private YfsiScoring() {}

    public static Result calculate(List<Integer> answers) {
        if (answers == null || answers.size() != QUESTION_COUNT
                || answers.stream().anyMatch(answer -> answer == null || answer < 1 || answer > 5)) {
            throw new IllegalArgumentException("30개 문항에 각각 1~5로 응답해야 합니다.");
        }

        var pressures = new ArrayList<PressureScore>();
        for (var pressure : Pressure.values()) {
            pressures.add(new PressureScore(pressure, score(answers, pressure.first, pressure.last, RISK_REVERSED)));
        }
        pressures.sort((first, second) -> second.score().compareTo(first.score()));

        var resources = List.of(
                new ResourceScore(Resource.FINANCIAL_LITERACY, score(answers, 18, 20, RESOURCE_REVERSED)),
                new ResourceScore(Resource.MONEY_MANAGEMENT, score(answers, 21, 23, RESOURCE_REVERSED)),
                new ResourceScore(Resource.SELF_EFFICACY, score(answers, 24, 26, RESOURCE_REVERSED))
        );

        return new Result(
                FORMULA_VERSION,
                score(answers, 1, 26, RISK_REVERSED),
                pressures.getFirst().pressure(),
                List.copyOf(pressures),
                resources,
                score(answers, 27, 29, Set.of()),
                OVERALL_STRESS.get(answers.get(29) - 1)
        );
    }

    private static Score score(List<Integer> answers, int first, int last, Set<Integer> reversed) {
        int pointsAboveMinimum = 0;
        for (int question = first; question <= last; question++) {
            int response = answers.get(question - 1);
            pointsAboveMinimum += (reversed.contains(question) ? 6 - response : response) - 1;
        }
        return new Score(25 * pointsAboveMinimum, last - first + 1);
    }
}
