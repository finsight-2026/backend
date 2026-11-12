package kr.finsight.diagnosis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public final class SurveyCatalog {
    public static final String SURVEY_VERSION = "yfsi-ko-v1";

    private static final List<String> AGREEMENT =
            List.of("전혀 그렇지 않다", "그렇지 않다", "보통이다", "그렇다", "매우 그렇다");
    private static final List<String> OVERALL_STRESS =
            List.of("매우 낮음", "낮음", "보통", "높음", "매우 높음");

    public record Option(int value, String label) {}
    public record Question(int number, String text, List<Option> options) {}
    public record Survey(String version, List<Question> questions) {}

    private final Survey current;

    public SurveyCatalog() throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("survey/" + SURVEY_VERSION + ".txt").getInputStream(), StandardCharsets.UTF_8))) {
            var texts = reader.lines().toList();
            if (texts.size() != YfsiScoring.QUESTION_COUNT || texts.stream().anyMatch(String::isBlank)) {
                throw new IllegalStateException("설문 원문에는 빈 줄 없이 30개 문항이 필요합니다.");
            }
            var agreement = options(AGREEMENT);
            var overallStress = options(OVERALL_STRESS);
            current = new Survey(SURVEY_VERSION, IntStream.range(0, texts.size())
                    .mapToObj(index -> new Question(index + 1, texts.get(index),
                            index == texts.size() - 1 ? overallStress : agreement))
                    .toList());
        }
    }

    public Survey current() {
        return current;
    }

    private static List<Option> options(List<String> labels) {
        return IntStream.range(0, labels.size()).mapToObj(i -> new Option(i + 1, labels.get(i))).toList();
    }
}
