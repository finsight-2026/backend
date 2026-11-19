# FINSIGHT 백엔드 배포

월 $20 상한에 맞춰 승인된 서울 Vultr Ubuntu VM 한 대에서 Docker Compose, Caddy, PostgreSQL을 실행한다. 스테이징과 운영은 각각 `staging.finsight.life`, `api.finsight.life`에서 서비스하며 같은 PostgreSQL 인스턴스에 별도 DB와 역할을 사용한다.

## 서버 준비

1. Vultr 인스턴스에서 Ubuntu LTS와 Docker Engine, Compose 플러그인, curl을 준비한다. 가비아의 `api`, `staging` A 레코드를 인스턴스 IPv4에 연결하고 공개 80·443과 관리용 SSH를 설정한다.
2. `/opt/finsight/database`에 `compose.database.yaml`, `init-database.sql`, 권한 600의 `database.env`를 설치한다. 예제의 세 비밀번호를 별도로 생성한다. `docker compose -f compose.database.yaml up -d --wait`로 PostgreSQL을 시작하면 `finsight`와 `finsight_staging`을 별도 소유자로 만든다. DB 포트는 호스트에 게시하지 않는다.
3. `/opt/finsight/staging`, `/opt/finsight/production`에 `compose.yaml`과 권한 640의 `runtime.env`를 배치한다. 해당 DB 소유자와 환경별 JWT·이메일 인증 키를 사용한다. 배포 사용자가 쓸 수 있는 `.release`에는 배포 잠금과 현재 이미지를 둔다.
4. `/opt/finsight/proxy`에 `compose.proxy.yaml`, `Caddyfile`을 두고 `docker compose -f compose.proxy.yaml up -d`로 Caddy를 실행한다. Caddy가 공개 TLS를 처리하고 Spring 포트는 호스트 루프백에만 바인딩된다. 공개 프록시는 관리자 API를 차단한다.
5. `deploy.sh`와 `authorized-release.sh`를 각각 `/usr/local/libexec/finsight-deploy`와 `/usr/local/libexec/finsight-authorized-release`에 설치한다. GitHub Actions 환경별 SSH 공개키는 해당 환경의 명령만 실행하도록 `restrict,command="/usr/local/libexec/finsight-authorized-release staging"` 또는 `production`으로 제한한다. 환경에 `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`를 설정한다. GHCR은 해당 작업의 단기 토큰으로 로그인하고 배포가 끝나면 임시 Docker 설정을 지운다.

운영과 스테이징은 서버에서 미리보기 약관을 비활성화한다. MailLink 연결 전에는 `FINSIGHT_MAIL_MODE=disabled`로 둔다. 인증번호와 비밀번호 재설정 요청에는 503을 반환한다. 정식 약관 버전과 운영 메일을 연결한 뒤 가입을 검증한다.

## 사용자 인증 메일

MailLink에 발신 주소를 등록하고 자동메일 템플릿 두 개를 승인받는다. 가입용 제목은 `[FINSIGHT] 이메일 인증번호`를 사용한다. 본문에 `[$_code_$]`와 유효기간 5분을 표시한다. 재설정용 제목은 `[FINSIGHT] 비밀번호 재설정`을 사용한다. 본문의 링크 목적지에 `[$_reset_link_$]`를 넣고 유효기간 15분과 본인이 요청하지 않았을 때의 안내를 표시한다. 승인된 템플릿별 `AU-숫자` 값을 `FINSIGHT_MAILLINK_SIGNUP_TEMPLATE`과 `FINSIGHT_MAILLINK_RESET_TEMPLATE`에 등록한다. MailLink는 수신 이메일 주소를 최대 60자로 제한한다. [자동메일 발송 API](https://www.maillink.co.kr/app/openapi/ag_sendautomail.jsp)

배포 환경의 `runtime.env`에 앱 키와 시크릿을 등록하고, 테스트 수신자로 발송을 승인받은 환경에서만 `FINSIGHT_MAIL_MODE=maillink`로 바꾼다. 응답의 대기열 건수와 최종 도착 여부를 MailLink 콘솔에서 각각 확인한다. iOS 재설정은 운영 링크와 앱 연결이 준비된 기기에서 검증한다. 로컬 테스트에서는 기본 `local` 모드로 개발 메일함을 사용한다.

재설정 링크의 호스트는 앱 Associated Domains와 같은 `finsight.life`다. 가비아의 루트 A 레코드를 인스턴스 IPv4에 연결하고 `Caddyfile`을 배치한다. 루트 호스트는 `/.well-known/apple-app-site-association`을 운영 API에서 직접 제공하고, 브라우저로 열린 `/password-reset`에는 앱에서 링크를 다시 여는 방법을 안내한다. 운영 서버의 `FINSIGHT_IOS_APP_ID`에 서명된 앱의 실제 `application-identifier`를 설정한다. 메일 발송을 켜기 전에 루트 HTTPS의 연결 파일이 리디렉션 없이 HTTP 200으로 응답하는지 확인하고 서명된 iPhone 빌드에서 앱 연결과 비밀번호 재설정을 검증한다. [Apple Associated Domains](https://developer.apple.com/documentation/xcode/supporting-associated-domains)

## 릴리스

- `develop`과 `main` PR에서 테스트와 컨테이너 빌드를 실행한다. `develop` 반영 시 GHCR 이미지의 digest를 스테이징에 배포한다.
- `main` 이력에 포함된 `vX.Y.Z` 태그에서 동일 digest를 스테이징에 배포하고 HTTPS 상태·설문 API가 성공하면 운영에 배포한다.
- 컨테이너·HTTPS 점검이 실패하면 직전 digest로 돌아간다. 직전 릴리스가 없는 경우 실패한 앱 컨테이너를 정지한다. 앱 이미지 복구는 DB 마이그레이션을 되돌리지 않으므로 이전 앱이 읽을 수 있는 마이그레이션을 적용한다.
- `backup-database.sh`는 운영 DB를 `pg_dump`로 백업하고 덤프 목록을 읽어 무결성을 확인한다. 서버는 한국시간 매일 03:30에 실행한다. Vultr 자동 백업은 별도로 켜져 있다. 두 백업 모두 서울 단일 서버 구성의 일부이므로 리전 장애 복구는 별도 인수한다.

[Caddy 자동 HTTPS](https://caddyserver.com/docs/quick-starts/https) · [Vultr 자동 백업](https://docs.vultr.com/vps-automatic-backups) · [GitHub 배포 환경](https://docs.github.com/en/actions/concepts/workflows-and-actions/deployment-environments)
