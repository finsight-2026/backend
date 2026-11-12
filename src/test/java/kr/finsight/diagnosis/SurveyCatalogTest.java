package kr.finsight.diagnosis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurveyCatalogTest {
    @Test
    void everyQuestionUsesTheSameFiveLevels() throws IOException {
        var questions = new SurveyCatalog().current().questions();
        assertEquals(30, questions.size());
        assertEquals(1, questions.getFirst().number());
        assertEquals(30, questions.getLast().number());
        for (var question : questions) {
            assertEquals(List.of(1, 2, 3, 4, 5),
                    question.options().stream().map(SurveyCatalog.Option::value).toList());
            assertEquals(List.of("매우낮음", "낮음", "보통", "높음", "매우높음"),
                    question.options().stream().map(SurveyCatalog.Option::label).toList());
        }
    }
}
