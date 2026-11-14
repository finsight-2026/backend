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

    private static final List<Option> RESPONSE_OPTIONS = List.of(
            new Option(1, "매우낮음"), new Option(2, "낮음"), new Option(3, "보통"),
            new Option(4, "높음"), new Option(5, "매우높음"));

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
            current = new Survey(SURVEY_VERSION, IntStream.range(0, texts.size())
                    .mapToObj(index -> new Question(index + 1, texts.get(index), RESPONSE_OPTIONS))
                    .toList());
        }
    }

    public Survey current() {
        return current;
    }

}
