package kr.finsight.admin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.finsight.Csv;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

final class AdminExportDocument {
    record Table(List<String> headings, float[] widths, List<List<String>> rows, int wrapColumn) {
        Table(List<String> headings, float[] widths, List<List<String>> rows) {
            this(headings, widths, rows, -1);
        }
        int pages() { return Math.max(1, (rows.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE); }
    }

    private static final int ROWS_PER_PAGE = 24;
    private static final float LEFT = 36;
    private static final float FONT_SIZE = 9;

    private AdminExportDocument() {}

    static byte[] csv(List<String> headings, List<List<String>> rows) {
        var result = new StringBuilder("\uFEFF");
        csvRow(result, headings);
        rows.forEach(row -> csvRow(result, row));
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void csvRow(StringBuilder output, List<String> row) {
        output.append(String.join(",", row.stream().map(Csv::safeCell).toList())).append("\r\n");
    }

    static byte[] pdf(String title, String period, List<Table> tables) throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream();
                var fontBytes = AdminExportDocument.class.getResourceAsStream("/fonts/Pretendard-Regular.ttf")) {
            if (fontBytes == null) throw new IOException("Report font unavailable");
            var font = PDType0Font.load(document, fontBytes);
            var landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            int totalPages = tables.stream().mapToInt(Table::pages).sum();
            int pageNo = 0;
            for (var table : tables) {
                for (int index = 0; index < table.pages(); index++) {
                    var page = new PDPage(landscape);
                    document.addPage(page);
                    try (var stream = new PDPageContentStream(document, page)) {
                        text(stream, font, LEFT, 553, 19, title);
                        text(stream, font, LEFT, 531, 10, period);
                        text(stream, font, LEFT, 25, 9, ++pageNo + " / " + totalPages);
                        float y = 510;
                        drawRow(stream, font, table.headings(), table.widths(), y, true, -1);
                        if (table.rows().isEmpty()) text(stream, font, LEFT, y - 24, FONT_SIZE, "조건에 맞는 항목이 없어요.");
                        for (int row = index * ROWS_PER_PAGE;
                             row < Math.min(table.rows().size(), (index + 1) * ROWS_PER_PAGE); row++) {
                            y -= 19;
                            drawRow(stream, font, table.rows().get(row), table.widths(), y, false, table.wrapColumn());
                        }
                    }
                }
            }
            document.getDocumentInformation().setTitle(title);
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void drawRow(PDPageContentStream stream, PDType0Font font, List<String> cells, float[] widths,
                                float baseline, boolean header, int wrapColumn) throws IOException {
        stream.setNonStrokingColor(header ? 0.91f : 0.97f, header ? 0.93f : 0.97f, 0.98f);
        stream.addRect(LEFT, baseline - 6, 769, 18);
        stream.fill();
        float x = LEFT;
        for (int column = 0; column < cells.size(); column++) {
            String value = printable(font, cells.get(column));
            float width = font.getStringWidth(value) * FONT_SIZE / 1000;
            float available = widths[column] - 12;
            if (column == wrapColumn && width > available) {
                twoLines(stream, font, x + 6, baseline, available, value);
            } else {
                float size = width <= available ? FONT_SIZE : FONT_SIZE * available / width;
                text(stream, font, x + 6, baseline, size, value);
            }
            x += widths[column];
        }
    }

    private static void twoLines(PDPageContentStream stream, PDType0Font font, float x, float baseline,
                                 float available, String value) throws IOException {
        int center = value.offsetByCodePoints(0, value.codePointCount(0, value.length()) / 2);
        int space = value.lastIndexOf(' ', center);
        int split = space > center * 3 / 4 ? space : center;
        String first = value.substring(0, split).strip();
        String second = value.substring(split).strip();
        float width = Math.max(font.getStringWidth(first), font.getStringWidth(second)) / 1000;
        float size = Math.min(7.5f, available / width);
        text(stream, font, x, baseline + 4, size, first);
        text(stream, font, x, baseline - 4, size, second);
    }

    private static String printable(PDType0Font font, String input) throws IOException {
        var value = input.replaceAll("\\p{Cntrl}", " ");
        try {
            font.getStringWidth(value);
            return value;
        } catch (IllegalArgumentException unavailable) {
            var out = new StringBuilder();
            for (int codePoint : value.codePoints().toArray()) {
                String character = Character.toString(codePoint);
                try {
                    font.getStringWidth(character);
                    out.append(character);
                } catch (IllegalArgumentException unsupported) {
                    out.append('?');
                }
            }
            return out.toString();
        }
    }

    private static void text(PDPageContentStream stream, PDType0Font font, float x, float y,
                             float size, String value) throws IOException {
        stream.setNonStrokingColor(0.09f, 0.12f, 0.19f);
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }
}
