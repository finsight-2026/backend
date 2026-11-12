package kr.finsight.account;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import kr.finsight.diagnosis.YfsiScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("local")
class ExportArchive {
    private final JdbcClient db;
    private final JsonMapper json;

    ExportArchive(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    byte[] build(UUID userId) {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("진단.csv"));
            var header = new ArrayList<String>(List.of("회차ID", "완료일시"));
            IntStream.rangeClosed(1, 30).forEach(n -> header.add("Q" + n));
            header.add("YFSI");
            for (var pressure : YfsiScoring.Pressure.values()) header.add(pressure.name());
            for (var resource : YfsiScoring.Resource.values()) header.add(resource.name());
            header.addAll(List.of("체감", "Q30 해석", "유형", "산식버전"));
            write(zip, "\ufeff");
            row(zip, header);
            db.sql("""
                    SELECT d.id,d.completed_at,d.score_snapshot,
                           ARRAY(SELECT da.response FROM diagnosis_answer da
                                 WHERE da.diagnosis_id = d.id ORDER BY da.question_no) AS answers
                    FROM diagnosis d JOIN survey_attempt a ON a.id = d.attempt_id
                    WHERE a.user_id = :user ORDER BY d.completed_at,d.id
                    """).param("user", userId).query((result, index) -> {
                var score = json.readValue(result.getString("score_snapshot"), YfsiScoring.Result.class);
                var columns = new ArrayList<String>();
                columns.add(result.getString("id"));
                columns.add(result.getObject("completed_at", OffsetDateTime.class).toInstant().toString());
                for (Object answer : (Object[]) result.getArray("answers").getArray()) columns.add(answer.toString());
                if (columns.size() != 32) throw new IllegalStateException("진단 응답 30개가 필요합니다.");
                columns.add(Integer.toString(score.total().display()));
                var pressures = new HashMap<YfsiScoring.Pressure, Integer>();
                score.rankedPressures().forEach(p -> pressures.put(p.pressure(), p.score().display()));
                for (var pressure : YfsiScoring.Pressure.values()) columns.add(String.valueOf(pressures.get(pressure)));
                var resources = new HashMap<YfsiScoring.Resource, Integer>();
                score.resources().forEach(r -> resources.put(r.resource(), r.score().display()));
                for (var resource : YfsiScoring.Resource.values()) columns.add(String.valueOf(resources.get(resource)));
                columns.addAll(List.of(Integer.toString(score.perceivedStress().display()),
                        score.overallStress(), score.representativeType().name(), score.formulaVersion()));
                row(zip, columns);
                return true;
            }).list();
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("가이드실천.csv"));
            write(zip, "\ufeff");
            row(zip, List.of("콘텐츠ID", "제목", "유형", "완료일시"));
            db.sql("""
                    SELECT content_id,title,representative_type,completed_at FROM guide_activity
                    WHERE user_id = :user AND completed_at IS NOT NULL ORDER BY completed_at,content_id
                    """).param("user", userId).query((result, index) -> {
                row(zip, List.of(result.getString(1), result.getString(2), result.getString(3),
                        result.getObject(4, OffsetDateTime.class).toInstant().toString()));
                return true;
            }).list();
            zip.closeEntry();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return bytes.toByteArray();
    }

    static String csvCell(String value) {
        return kr.finsight.Csv.safeCell(value);
    }

    private static void row(ZipOutputStream zip, List<String> values) {
        write(zip, String.join(",", values.stream().map(ExportArchive::csvCell).toList()) + "\r\n");
    }

    private static void write(ZipOutputStream zip, String text) {
        try {
            zip.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
