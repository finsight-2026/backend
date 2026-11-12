# FINSIGHT 백엔드 배포

이 구성은 서울의 Vultr Ubuntu VM에서 Docker Compose와 Caddy를 실행하고, 별도의 Vultr 관리형 PostgreSQL을 사용한다. 스테이징과 운영은 각각 `staging.finsight.life`, `api.finsight.life`에서 서비스하며 서로 다른 DB와 자격 증명을 사용한다.

## 서버 준비

1. Vultr 인스턴스에서 Ubuntu LTS와 Docker Engine, Compose 플러그인, curl을 준비한다. 가비아의 `api`, `staging` A 레코드를 인스턴스 IPv4에 연결하고 공개 80·443과 관리용 SSH를 설정한다. 관리형 DB는 애플리케이션 서버의 접근만 허용한다.
2. 배포 사용자로 `/opt/finsight/staging`, `/opt/finsight/production` 디렉터리를 준비한다. 각 디렉터리에 `compose.yaml`, 관리형 DB의 공개 CA 인증서 `postgres-ca.pem`, 권한 600의 `runtime.env`를 배치한다. `runtime.env.example`을 기준으로 DB URL과 비밀값을 넣고 환경마다 JWT·이메일 인증 키를 별도로 생성한다. 인증서의 이름 검증을 포함한 PostgreSQL TLS를 사용한다.
3. 호스트의 배포 사용자가 비공개 GHCR 패키지를 읽을 수 있도록 읽기 전용 자격 증명으로 `docker login ghcr.io`를 실행한다. GitHub Actions 환경 `staging`, `production`에는 `DEPLOY_HOST`, `DEPLOY_USER` 변수와 `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS` 비밀값을 설정한다. SSH 호스트 공개키는 Vultr 콘솔 등 독립 경로에서 확인한 값을 사용한다.
4. 별도 디렉터리에 `compose.proxy.yaml`, `Caddyfile`을 두고 `docker compose -f compose.proxy.yaml up -d`로 Caddy를 한 번 실행한다. Caddy가 공개 TLS를 처리하고 Spring 포트는 호스트 루프백에서만 열린다. 공개 프록시는 관리자 API를 숨긴다.

운영과 스테이징은 서버에서 미리보기 약관을 비활성화한다. MailLink 연결 전에는 `FINSIGHT_MAIL_MODE=disabled`로 둔다. 인증번호와 비밀번호 재설정 요청에는 503을 반환한다. 정식 약관 버전과 운영 메일을 연결한 뒤 가입을 검증한다.

## 릴리스

- `develop`과 `main` PR에서 테스트와 컨테이너 빌드를 실행한다. `develop` 반영 시 GHCR 이미지의 digest를 스테이징에 배포한다.
- `main` 이력에 포함된 `vX.Y.Z` 태그에서 동일 digest를 스테이징에 배포하고 HTTPS 상태·설문 API가 성공하면 운영에 배포한다.
- 컨테이너·HTTPS 점검이 실패하면 직전 digest로 돌아간다. 직전 릴리스가 없는 경우 실패한 앱 컨테이너를 정지한다. 앱 이미지 복구는 DB 마이그레이션을 되돌리지 않으므로 이전 앱이 읽을 수 있는 마이그레이션을 적용한다.

[Caddy 자동 HTTPS](https://caddyserver.com/docs/quick-starts/https) · [Vultr 관리형 PostgreSQL](https://docs.vultr.com/products/storage/databases/postgresql/provisioning) · [GitHub 배포 환경](https://docs.github.com/en/actions/concepts/workflows-and-actions/deployment-environments)
