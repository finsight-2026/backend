-- Extend the existing editorial workflow to the seeded and newly edited app guides.
ALTER TABLE service_notice ADD COLUMN kind text NOT NULL DEFAULT 'NOTICE' CHECK(kind IN ('NOTICE','GUIDE'));
ALTER TABLE service_notice ADD COLUMN external_key text UNIQUE;
ALTER TABLE service_notice_revision ALTER COLUMN author_id DROP NOT NULL;
ALTER TABLE service_notice_revision ADD COLUMN types text[];
ALTER TABLE service_notice_revision ADD COLUMN today_action text;
ALTER TABLE service_notice_revision ADD COLUMN next_action text;
ALTER TABLE service_notice_revision ADD COLUMN guide_category text CHECK(guide_category IN ('START','LEARN'));
ALTER TABLE service_notice_revision ADD CHECK(author_id IS NOT NULL OR revision_no=1);

CREATE TEMP TABLE editorial_guide_seed (
    external_key text, guide_type text, title text, heading text, today_action text, next_action text,
    category text, sort_order integer
) ON COMMIT DROP;
INSERT INTO editorial_guide_seed VALUES
    ('FUTURE-1', 'FUTURE', '지출부터 파악하기', '고정지출부터 한 번 살펴봐요', '이번 달 자동 결제 목록 확인', '자동결제 확인
사용 빈도 살펴보기
해지 전 혜택과 불이익 확인하기', 'START', 1),
    ('FUTURE-2', 'FUTURE', '비상금 만들기', '비상자금 목표를 정해볼까요?', '이번 달 필수 생활비 적어보기', '필수지출 확인
가능한 금액 정하기
따로 보관하기', 'START', 2),
    ('FUTURE-3', 'FUTURE', '실전 시뮬레이션', '다음 달을 미리 그려봐요', '다음 달 예상 수입·지출 써보기', '예상 수입 적기
필수 지출 적기
부족할 때의 선택 정하기', 'START', 3),
    ('FUTURE-4', 'FUTURE', '전문가 상담이 필요할 때', '혼자 고민하지 않아도 돼요', '상담 전 궁금한 점 메모하기', '고민 정리하기
공식 상담채널 확인
필요한 서류 점검하기', 'LEARN', 4),
    ('DEBT-1', 'DEBT', '부채부터 파악하기', '내 부채부터 한 번 정리해봐요', '현재 대출·카드 상환 내역 적기', '부채 목록 작성
남은 금액 확인
월 상환액 확인', 'START', 1),
    ('DEBT-2', 'DEBT', '상환 일정 확인하기', '언제 얼마를 갚는지 확인해봐요', '다음 상환일과 금리 확인하기', '상환일 확인
금리 확인
달력에 표시하기', 'START', 2),
    ('DEBT-3', 'DEBT', '상환 계획 세우기', '내가 감당할 수 있는 범위를 살펴봐요', '이번 달 상환 가능 금액 확인하기', '월 수입 확인
필수생활비 제외
상환 가능 금액 확인', 'START', 3),
    ('DEBT-4', 'DEBT', '전문가 상담이 필요할 때', '상환 부담을 혼자 정리하지 않아도 돼요', '상담하고 싶은 내용 적어보기', '현재 상황 정리
공식 상담채널 확인
필요한 자료 준비', 'LEARN', 4),
    ('INCOME-1', 'INCOME', '다음 달 수입 확인하기', '다음 달 들어올 돈부터 살펴봐요', '다음 달 확정 수입 적기', '확정 수입 확인
예상 수입 구분
전체 예상 금액 확인', 'START', 1),
    ('INCOME-2', 'INCOME', '필수지출 파악하기', '꼭 필요한 지출부터 확인해봐요', '이번 달 필수지출 적기', '주거비 확인
생활비 확인
월 최소생활비 계산', 'START', 2),
    ('INCOME-3', 'INCOME', '소득 공백 대비하기', '수입이 줄어드는 상황도 준비해봐요', '줄일 수 있는 지출 3개 표시하기', '조정 가능 지출 찾기
예상 절감액 확인
대응 순서 정하기', 'START', 3),
    ('INCOME-4', 'INCOME', '지원정보 확인하기', '이용할 수 있는 지원을 확인해봐요', '현재 확인할 지원제도 1개 찾아보기', '대상조건 확인
신청기간 확인
필요한 서류 확인', 'LEARN', 4),
    ('EMERGENCY_FUND-1', 'EMERGENCY_FUND', '생활비부터 파악하기', '한 달에 꼭 필요한 돈부터 알아봐요', '이번 달 필수생활비 적어보기', '주거비 확인
생활비 확인
한 달 필수생활비 계산', 'START', 1),
    ('EMERGENCY_FUND-2', 'EMERGENCY_FUND', '비상금 목표 세우기', '작은 비상금 목표부터 정해봐요', '내가 가능한 첫 목표 금액 정하기', '목표금액
월 적립액
목표기간 정하기', 'START', 2),
    ('EMERGENCY_FUND-3', 'EMERGENCY_FUND', '따로 보관하기', '비상금은 생활비와 나눠볼까요?', '비상금 보관 장소 정하기', '분리 계좌 선택
적립 방법 결정
사용 기준 정하기', 'START', 3),
    ('EMERGENCY_FUND-4', 'EMERGENCY_FUND', '갑작스러운 지출 대비하기', '예상하지 못한 지출도 미리 생각해봐요', '갑작스러운 지출 3개 적기', '가능성 확인
예상금액
대응방법 정하기', 'LEARN', 4),
    ('FIXED_COST-1', 'FIXED_COST', '자동결제 확인하기', '고정지출부터 한 번 살펴봐요', '이번 달 자동결제 목록 확인', '자동결제 확인
사용 빈도 확인
조정 가능 항목 표시', 'START', 1),
    ('FIXED_COST-2', 'FIXED_COST', '고정비 나눠보기', '매달 꼭 나가는 돈을 구분해봐요', '이번 달 지출을 고정비와 변동비로 나누기', '고정비 확인
변동비 확인
각각의 합계 확인', 'START', 2),
    ('FIXED_COST-3', 'FIXED_COST', '조정 가능한 항목 찾기', '바꿀 수 있는 항목부터 살펴봐요', '통신·구독 등 조정 가능한 항목 표시하기', '사용 여부 확인
필요성 확인
변경 시 조건 확인', 'START', 3),
    ('FIXED_COST-4', 'FIXED_COST', '한 달 고정비 확인하기', '한 달 고정비가 얼마인지 확인해봐요', '월 고정비 총액 계산하기', '항목별 금액 확인
전체 합계 계산
부담 큰 항목 표시', 'LEARN', 4),
    ('FAMILY-1', 'FAMILY', '가족지원 지출 파악하기', '가족을 위해 나가는 돈부터 확인해봐요', '최근 가족지원 지출 적기', '정기지출 확인
비정기지출 확인
월평균 계산', 'START', 1),
    ('FAMILY-2', 'FAMILY', '내 생활비 구분하기', '나에게 꼭 필요한 생활비도 확인해봐요', '본인 필수생활비 계산하기', '필수생활비 확인
가족지원금 확인
남는 금액 확인', 'START', 2),
    ('FAMILY-3', 'FAMILY', '지원 한도 정하기', '내가 감당할 수 있는 범위를 정해봐요', '한 달 지원 가능 금액 적기', '내 생활비 확보
지원금액 설정
초과 시 대응방법 정하기', 'START', 3),
    ('FAMILY-4', 'FAMILY', '반복 지출 확인하기', '계속 발생하는 지원 비용을 살펴봐요', '정기적으로 지원하는 항목 표시하기', '반복 항목 확인
월평균 계산
앞으로 필요한 금액 예상', 'LEARN', 4);

INSERT INTO service_notice(id,kind,external_key)
SELECT md5('guide:'||external_key)::uuid,'GUIDE',external_key FROM editorial_guide_seed;
INSERT INTO service_notice_revision(id,notice_id,revision_no,status,title,summary,body,sort_order,starts_at,
                                    types,today_action,next_action,guide_category)
SELECT md5('guide-revision:'||external_key||':1')::uuid,md5('guide:'||external_key)::uuid,1,'PUBLISHED',
       title,heading,heading,sort_order,'2026-01-01T00:00:00Z',ARRAY[guide_type],today_action,next_action,category
FROM editorial_guide_seed;
UPDATE service_notice SET published_revision_id=md5('guide-revision:'||external_key||':1')::uuid WHERE kind='GUIDE';

CREATE VIEW published_guide AS
SELECT n.id,coalesce(n.external_key,n.id::text) AS content_key,r.revision_no,r.types,r.title,r.summary,r.body,
       r.today_action,r.next_action,r.guide_category,r.sort_order
FROM service_notice n JOIN service_notice_revision r ON r.id=n.published_revision_id
WHERE n.kind='GUIDE' AND r.status='PUBLISHED' AND r.starts_at<=statement_timestamp()
    AND (r.ends_at IS NULL OR r.ends_at>statement_timestamp());

ALTER TABLE diagnosis_view_event ADD COLUMN content_revision integer CHECK(content_revision>0);
ALTER TABLE guide_activity ADD COLUMN last_completion_revision integer CHECK(last_completion_revision>0);
ALTER TABLE guide_activity ADD COLUMN last_completion_type text;

CREATE OR REPLACE FUNCTION log_app_diagnosis_client_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    adjusted boolean := coalesce(abs(extract(epoch FROM NEW.client_occurred_at-NEW.recorded_at))>300,false);
    occurred timestamptz := CASE WHEN adjusted THEN NEW.recorded_at ELSE coalesce(NEW.client_occurred_at,NEW.recorded_at) END;
BEGIN
    IF TG_TABLE_NAME='diagnosis_question_event' THEN
        INSERT INTO app_event(event_id,event_name,user_id,attempt_id,journey_id,question_no,active_ms,occurred_at,received_at,clock_adjusted)
        SELECT NEW.id,CASE NEW.kind WHEN 'VIEWED' THEN 'QUESTION_VIEWED' ELSE 'QUESTION_COMPLETED' END,
               a.user_id,a.id,a.journey_id,NEW.question_no,NEW.active_ms,occurred,NEW.recorded_at,adjusted
        FROM survey_attempt a WHERE a.id=NEW.attempt_id ON CONFLICT(event_id) DO NOTHING;
    ELSE
        INSERT INTO app_event(event_id,event_name,user_id,attempt_id,journey_id,diagnosis_id,content_id,content_kind,content_revision,content_type,occurred_at,received_at,clock_adjusted)
        SELECT NEW.id,CASE WHEN NEW.content_id IS NULL THEN 'RESULT_VIEWED' ELSE 'GUIDE_VIEWED' END,
               a.user_id,a.id,a.journey_id,d.id,NEW.content_id,
               CASE WHEN NEW.content_id IS NOT NULL THEN 'GUIDE' END, NEW.content_revision,
               CASE WHEN NEW.content_id IS NOT NULL THEN d.representative_type END,occurred,NEW.recorded_at,adjusted
        FROM survey_attempt a JOIN diagnosis d ON d.attempt_id=a.id WHERE a.id=NEW.attempt_id
        ON CONFLICT(event_id) DO NOTHING;
    END IF;
    IF NOT FOUND THEN RETURN NULL; END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION add_guide_event_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.content_revision IS NULL THEN
        SELECT 'GUIDE',
            CASE WHEN NEW.event_name='GUIDE_COMPLETION_SET' THEN coalesce(g.last_completion_revision,g.content_version)
                ELSE g.content_version END,
            CASE WHEN NEW.event_name='GUIDE_COMPLETION_SET' THEN coalesce(g.last_completion_type,g.representative_type)
                ELSE g.representative_type END
        INTO NEW.content_kind,NEW.content_revision,NEW.content_type
        FROM guide_activity g WHERE g.user_id=NEW.user_id AND g.content_id=NEW.content_id
            AND g.month_start<=date_trunc('month',NEW.received_at AT TIME ZONE 'Asia/Seoul')::date
        ORDER BY g.month_start DESC LIMIT 1;
    END IF;
    RETURN NEW;
END;
$$;
