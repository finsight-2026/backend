package kr.finsight.diagnosis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class YfsiScoringTest {
    @Test
    void neutralAnswersTieInSpecificationOrder() {
        var result = YfsiScoring.calculate(Collections.nCopies(30, 3));
        assertEquals(50, result.total().display());
        assertEquals(50, result.perceivedStress().display());
        assertEquals("보통", result.overallStress());
        assertEquals(YfsiScoring.Pressure.DEBT, result.representativeType());
        assertEquals(List.of(YfsiScoring.Pressure.values()),
                result.rankedPressures().stream().map(YfsiScoring.PressureScore::pressure).toList());
        assertTrue(result.rankedPressures().stream().allMatch(pressure -> pressure.score().display() == 50));
        assertTrue(result.resources().stream().allMatch(resource -> resource.score().display() == 50));
    }

    @Test
    void reversalProducesBothExtremesAndOppositeResourceDirection() {
        var reversed = Set.of(3, 5, 9, 10, 14, 18, 20, 21, 23, 24, 26);
        var lowestRisk = IntStream.rangeClosed(1, 30).mapToObj(i -> reversed.contains(i) ? 5 : 1).toList();
        var highestRisk = lowestRisk.stream().map(answer -> 6 - answer).toList();
        var low = YfsiScoring.calculate(lowestRisk);
        var high = YfsiScoring.calculate(highestRisk);
        assertEquals(0, low.total().display());
        assertEquals(100, high.total().display());
        assertTrue(low.resources().stream().allMatch(resource -> resource.score().display() == 100));
        assertTrue(high.resources().stream().allMatch(resource -> resource.score().display() == 0));
        assertEquals(42, YfsiScoring.calculate(Collections.nCopies(30, 1)).total().display());
        assertEquals(58, YfsiScoring.calculate(Collections.nCopies(30, 5)).total().display());
    }

    @Test
    void subjectiveAnswersOnlyAffectTheirOwnFields() {
        var answers = new ArrayList<>(Collections.nCopies(30, 3));
        var original = YfsiScoring.calculate(answers);
        answers.subList(26, 30).replaceAll(value -> 5);
        var changed = YfsiScoring.calculate(answers);
        assertEquals(original.total(), changed.total());
        assertEquals(original.rankedPressures(), changed.rankedPressures());
        assertEquals(original.representativeType(), changed.representativeType());
        assertEquals(100, changed.perceivedStress().display());
        assertEquals("매우 높음", changed.overallStress());
    }

    @Test
    void displayRoundsHalfUpWithoutRoundingComparisons() {
        var half = new YfsiScoring.Score(25, 2);
        var higher = new YfsiScoring.Score(39, 3);
        assertEquals(13, half.display());
        assertEquals(13, higher.display());
        assertTrue(half.compareTo(higher) < 0);
        assertEquals(0, new YfsiScoring.Score(100, 2).compareTo(new YfsiScoring.Score(150, 3)));
    }

    @Test
    void missingAndOutOfRangeAnswersAreRejected() {
        var answers = new ArrayList<>(Collections.nCopies(30, 3));
        assertThrows(IllegalArgumentException.class, () -> YfsiScoring.calculate(answers.subList(0, 29)));
        answers.set(29, null);
        assertThrows(IllegalArgumentException.class, () -> YfsiScoring.calculate(answers));
        answers.set(29, 6);
        assertThrows(IllegalArgumentException.class, () -> YfsiScoring.calculate(answers));
    }
}
