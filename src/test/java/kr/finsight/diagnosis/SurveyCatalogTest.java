package kr.finsight.diagnosis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurveyCatalogTest {
    @Test
    void onlyLastQuestionUsesTheStressScale() throws IOException {
        var questions = new SurveyCatalog().current().questions();
        assertEquals(30, questions.size());
        assertEquals(1, questions.getFirst().number());
        assertEquals(30, questions.getLast().number());
        assertEquals(List.of(1, 2, 3, 4, 5),
                questions.getLast().options().stream().map(SurveyCatalog.Option::value).toList());
        assertEquals("전혀 그렇지 않다", questions.get(28).options().getFirst().label());
        assertEquals("매우 낮음", questions.getLast().options().getFirst().label());
    }
}
