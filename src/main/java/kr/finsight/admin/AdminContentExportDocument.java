package kr.finsight.admin;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class AdminContentExportDocument {
    static final List<String> COLUMNS = List.of("kind", "contentId", "title", "revision", "impressions",
            "views", "ctrNumerator", "ctrDenominator", "ctr", "helpful", "completions");
    private static final List<String> HEADINGS = List.of("종류", "콘텐츠 ID", "제목", "버전", "노출",
            "상세 조회", "조회율 분자", "조회율 분모", "조회율 (%)", "도움 반응", "실천 완료");
    private static final List<String> CSV_HEADINGS = List.of("구분", "콘텐츠 ID 또는 기준 항목", "제목 또는 기준 값",
            "버전", "노출", "상세 조회", "조회율 분자", "조회율 분모", "조회율 (%)", "도움 반응", "실천 완료");

    private final List<List<String>> metadata;
    private final List<List<String>> rows;
    private final String period;

    AdminContentExportDocument(AdminContentPerformance.Filter filter, AdminContentPerformance.Report report, UUID owner) {
        period = report.from() + " ~ " + report.to() + " (KST)";
        metadata = List.of(
                List.of("조회 기간", period + " · 시작일과 종료일 포함"),
                List.of("콘텐츠 종류", filter.kind() == null ? "전체" : kind(filter.kind().name())),
                List.of("대표 유형", filter.type() == null ? "전체" : filter.type().badge),
                List.of("콘텐츠 ID", filter.contentId() == null ? "전체" : filter.contentId()),
                List.of("버전", filter.combineVersions() ? "버전 합산" : filter.revision() == null ? "버전별 전체" : filter.revision().toString()),
                List.of("요청 관리자 ID", owner.toString()),
                List.of("집계 기준 (KST)", time(report.asOf())),
                List.of("계측 시작 (KST)", time(report.observedAt())),
                List.of("잠정 집계", report.provisional() ? "예" : "아니요"),
                List.of("노출·상세 조회", "조회 기간 내 각 이벤트의 고유 사용자 수"),
                List.of("조회율", "같은 기간 노출과 상세 조회가 모두 있는 사용자 / 노출 사용자 × 100. 분모가 0이면 빈 값."),
                List.of("도움 반응", "기간 종료 시점에 유효한 인사이트 도움 반응. 취소와 삭제를 차감."),
                List.of("가이드 실천 완료", report.completionMonth().toString().substring(0, 7) + "의 현재 월별 실천 완료 상태"),
                List.of("미지원 지표", "빈 값으로 표시")
        );
        rows = report.items().stream().map(row -> List.of(kind(row.kind()), row.contentId(), row.title(),
                row.revision() == null ? "합산" : row.revision().toString(), value(row.impressions()), value(row.views()),
                value(row.ctrNumerator()), value(row.impressions()), value(row.ctr()), value(row.helpful()), value(row.completions()))).toList();
    }

    byte[] csv() {
        var records = new ArrayList<List<String>>(metadata.size() + rows.size());
        metadata.forEach(item -> records.add(List.of("집계 기준", item.getFirst(), item.getLast(), "", "", "", "", "", "", "", "")));
        records.addAll(rows);
        return AdminExportDocument.csv(CSV_HEADINGS, records);
    }

    byte[] pdf() throws IOException {
        return AdminExportDocument.pdf("FINSIGHT 콘텐츠 성과", period, List.of(
                new AdminExportDocument.Table(List.of("집계 기준", "값"), new float[]{145, 624}, metadata),
                new AdminExportDocument.Table(HEADINGS, new float[]{61, 126, 170, 40, 47, 53, 56, 56, 58, 50, 52}, rows, 2)));
    }

    private static String kind(String value) {
        return switch (value) {
            case "NEWS" -> "경제 뉴스";
            case "GUIDE" -> "맞춤 가이드";
            case "INSIGHT" -> "행동 인사이트";
            default -> value;
        };
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }

    private static String time(OffsetDateTime value) {
        return value.atZoneSameInstant(AdminUsers.KST).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
