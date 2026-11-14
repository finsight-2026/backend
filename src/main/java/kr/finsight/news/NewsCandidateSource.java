package kr.finsight.news;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

interface NewsCandidateSource {
    record Item(String sourceUrl, String title, OffsetDateTime articlePublishedAt) {}
    Optional<List<Item>> read() throws IOException;
}

@Component
@Profile("local")
class LocalNewsCandidateSource implements NewsCandidateSource {
    private static final int MAX_BYTES = 1024 * 1024;
    private final String file;
    private final JsonMapper json;

    LocalNewsCandidateSource(@Value("${FINSIGHT_NEWS_CANDIDATE_FILE:}") String file, JsonMapper json) {
        this.file = file;
        this.json = json;
    }

    @Override
    public Optional<List<Item>> read() throws IOException {
        if (file.isBlank()) return Optional.empty();
        var path = Path.of(file);
        if (!Files.isRegularFile(path)) throw new IOException("후보 파일을 열 수 없습니다.");
        try (var input = Files.newInputStream(path)) {
            var bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("후보 파일 크기 제한을 넘었습니다.");
            var rows = json.readTree(bytes);
            if (rows == null || !rows.isArray() || rows.size() > 500) throw new IOException("후보 목록 형식이 잘못되었습니다.");
            var result = new ArrayList<Item>();
            for (var row : rows) {
                if (!row.path("sourceUrl").isString() || !row.path("title").isString()
                        || !row.path("articlePublishedAt").isString()) continue;
                try {
                    result.add(new Item(row.path("sourceUrl").asText(""), row.path("title").asText(""),
                            OffsetDateTime.parse(row.path("articlePublishedAt").asText(""))));
                } catch (DateTimeParseException ignored) {
                    // A row with an invalid date cannot be an editorial candidate.
                }
            }
            return Optional.of(List.copyOf(result));
        } catch (tools.jackson.core.JacksonException malformed) {
            throw new IOException("후보 파일이 유효한 JSON이 아닙니다.", malformed);
        }
    }
}
