package kr.finsight.admin;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.finsight.diagnosis.YfsiScoring;

final class AdminDashboardExportDocument {
    static final List<String> COLUMNS = List.of("section", "metric", "value", "denominator", "definition");
    private static final List<String> HEADINGS = List.of("구분", "지표", "값", "분모", "산식 및 집계 범위");
    private static final float[] WIDTHS = {82, 145, 170, 55, 317};

    private final List<List<String>> rows = new ArrayList<>();

    AdminDashboardExportDocument(AdminDashboard.Filter filter, AdminDashboard.Dashboard data, UUID requester) {
        add("기준", "기간 (KST)", filter.from() + " ~ " + filter.to(), null, "시작일과 종료일을 포함");
        add("기준", "연령대", filter.age() == null ? "전체" : filter.age(), null, "현재 가입 프로필");
        add("기준", "대표 유형", filter.type() == null ? "전체" : filter.type().badge, null, "기간 내 사용자별 마지막 완료 진단");
        add("기준", "집계 시각 (KST)", data.syncedAt().atZoneSameInstant(AdminUsers.KST).toOffsetDateTime()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS), null, "서버 집계 스냅샷");
        add("기준", "요청 관리자 ID", requester, null, "분석 담당자");
        add("기준", "점수 산식 버전", YfsiScoring.FORMULA_VERSION, null, "미관측 및 분모 0인 비율은 빈 값");
        var totals = data.totals();
        var activity = data.activity();
        add("가입과 진단", "신규 가입", totals.registrations(), null, "기간 내 가입 계정");
        add("가입과 진단", "전체 가입 계정", totals.accounts(), null, "종료일 기준 집계 대상 계정");
        add("가입과 진단", "진단 시작", totals.attempts(), null, "기간 내 시작한 진단 시도");
        add("가입과 진단", "24시간 내 진단 완료", totals.completedWithin24Hours(), null, "시작 후 24시간 안에 완료한 시도");
        ratio("가입과 진단", "진단 완료율", totals.completedWithin24Hours(), totals.attempts(), "24시간 내 완료 / 진단 시작");
        add("가입과 진단", "완료율 잠정 시도", totals.provisionalAttempts(), null, "아직 24시간 관측이 끝나지 않은 미완료 시도");
        add("가입과 진단", "진단 사용자", totals.diagnosedUsers(), null, "기간 내 진단을 완료한 고유 사용자");
        add("가입과 진단", "완료 진단 평균 활동시간 (ms)", data.responseTime().averageActiveMs(),
                data.responseTime().measuredAttempts(), "30문항 활동시간이 기록된 24시간 내 완료 시도 평균");
        add("점수", "YFSI 평균", totals.meanYfsi(), totals.yfsiCount(), "사용자별 마지막 진단 총점의 평균");
        add("점수", "체감 스트레스 평균", totals.meanPerceivedStress(), totals.perceivedStressCount(), "사용자별 마지막 진단 Q27~29 점수의 평균");
        add("점수", "외부 압박 종합 평균", totals.meanPressure(), totals.pressureCount(), "사용자별 마지막 진단 Q1~17 위험 방향 점수 합 / 17의 평균");
        add("점수", "대응자원 종합 평균", totals.meanResources(), totals.resourcesCount(), "사용자별 마지막 진단 Q18~26 긍정 방향 점수 합 / 9의 평균");
        add("활동", "수집 시작일", activity.observedFrom(), null, "포그라운드 이벤트 관측 시작일");
        add("활동", "기간 내 활성 사용자", activity.periodActive(), null, "기간 내 포그라운드 진입 고유 사용자");
        add("활동", "종료일 DAU", activity.dau(), null, "종료일 포그라운드 진입 고유 사용자");
        add("활동", "종료일 기준 MAU", activity.mau(), null, "종료일까지 최근 30일 포그라운드 진입 고유 사용자");
        ratio("활동", "가입 7일 재방문율", activity.d7Returned(), activity.d7Eligible(), "가입일 + 7일 재방문 / 관측을 마친 가입자");
        ratio("가이드", "가이드 열람률", data.guideRate().guideUsers(), data.guideRate().resultUsers(),
                "같은 회차 결과 이후 가이드 열람자 / 결과 열람자");
        var funnel = data.signupFunnel();
        add("신규가입 퍼널", "가입", funnel.signups(), null, "기간 내 가입 고유 사용자");
        add("신규가입 퍼널", "필수 정보 및 동의", funnel.profiles(), funnel.signups(), "가입 후 7일 내 순차 도달");
        add("신규가입 퍼널", "진단 시작", funnel.started(), funnel.profiles(), "가입 후 7일 내 순차 도달");
        add("신규가입 퍼널", "진단 완료", funnel.completed(), funnel.started(), "가입 후 7일 내 순차 도달");
        add("신규가입 퍼널", "결과 열람", funnel.results(), funnel.completed(), "가입 후 7일 내 순차 도달");
        add("신규가입 퍼널", "가이드 열람", funnel.guides(), funnel.results(), "가입 후 7일 내 같은 회차 결과 이후 도달");
        add("신규가입 퍼널", "잠정 가입자", funnel.provisional(), funnel.signups(), "가입 후 관측 기간 7일 미경과");
        data.scoreDistribution().forEach(bucket -> add("YFSI 분포", bucket.lower() + "점대", bucket.count(),
                totals.yfsiCount(), "사용자별 마지막 진단, 마지막 구간은 100점 포함"));
        data.typeDistribution().forEach(item -> add("대표 유형 분포", YfsiScoring.Pressure.valueOf(item.type()).badge,
                item.count(), totals.diagnosedUsers(), "사용자별 마지막 완료 진단의 대표 유형"));
        data.daily().forEach(day -> {
            add("일별 신규 가입", day.date().toString(), day.registrations(), null, "KST 가입일");
            add("일별 진단 시작", day.date().toString(), day.attempts(), null, "KST 진단 시작일");
            add("일별 진단 완료", day.date().toString(), day.completedWithin24Hours(), day.attempts(),
                    "해당 KST 날짜에 시작하고 24시간 안에 완료한 시도");
        });
        data.cohorts().forEach(cell -> add("주간 코호트", cell.week() + " W" + cell.offset(), cell.active(),
                cell.registrations(), "월요일 기준 가입 주차별 활성 사용자 / 해당 주차 가입자"));
    }

    private void ratio(String section, String metric, long numerator, long denominator, String definition) {
        var value = denominator == 0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP) + "%";
        add(section, metric, value, denominator, definition + " × 100 (분자 " + numerator + ")");
    }

    private void add(String section, String metric, Object value, Object denominator, String definition) {
        rows.add(List.of(section, metric, value == null ? "" : value.toString(),
                denominator == null ? "" : denominator.toString(), definition));
    }

    byte[] csv() {
        return AdminExportDocument.csv(HEADINGS, rows);
    }

    byte[] pdf() throws IOException {
        return AdminExportDocument.pdf("FINSIGHT 대시보드 집계", rows.getFirst().get(2) + " (KST)",
                List.of(new AdminExportDocument.Table(HEADINGS, WIDTHS, rows)));
    }
}
