package kr.finsight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CsvTest {
    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+1+1", "-1+1", "@SUM(1)", "  =1+1", "\tvalue", "\rvalue", "\nvalue"})
    void prefixesSpreadsheetFormulaAndControlCharacters(String value) {
        assertEquals("\"'" + value + "\"", Csv.safeCell(value));
    }
}
