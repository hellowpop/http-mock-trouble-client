# HTTP Mock Trouble Client

HTTP 서버에 잘못된 요청을 보내거나 불완전한 연결을 유지하여 서버의 응답과 소켓 종료 동작을 관찰하는 Java 21 테스트 클라이언트입니다.
하나의 실행 JAR에서 CLI와 Swing 애플리케이션을 선택할 수 있습니다.

## 실행 환경

- 실행: Java 21 이상
- 소스 빌드: JDK 21 이상, Maven 3.9
- Swing 실행: 그래픽 데스크톱 환경
- 테스트할 HTTP 또는 HTTPS 서버

대상 서버를 먼저 실행하고 `target-server-url`에 해당 서버의 주소를 지정합니다.
클라이언트는 대상 서버를 시작하지 않습니다.

## 빠른 시작

```shell
git clone https://github.com/hellowpop/http-mock-trouble-client.git
cd http-mock-trouble-client
java -version
mvn -version
mvn clean verify
```

테스트와 빌드가 완료되면 의존성을 포함한 `target/http-mock-trouble-client.jar`가 생성됩니다.
배포 시 JAR와 사용할 YAML 설정 파일을 복사하면 됩니다. 실행 환경에는 Maven이 필요하지 않습니다.

도움말과 CLI 실행:

```shell
java -jar target/http-mock-trouble-client.jar --help
java -jar target/http-mock-trouble-client.jar --config config/example.yml
```

Swing 실행:

```shell
java -jar target/http-mock-trouble-client.jar --config config/example.yml --mode application
```

예제 설정의 대상은 `http://localhost:8080/`입니다. 다른 서버를 사용할 때는 다음처럼 덮어씁니다.

```shell
java -jar target/http-mock-trouble-client.jar --config config/example.yml --target-server-url http://localhost:9090/test
```

Windows PowerShell에서 다른 Java 버전이 선택되어 있으면 실제 JDK 21 설치 경로를 지정합니다.
아래 경로는 이 프로젝트 개발 PC의 설치 경로입니다.

```powershell
$env:JAVA_HOME = 'D:\01.app\java\jdk-21.0.3'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
mvn -version
```

## CLI 사용법

CLI는 설정에 지정한 타입을 배열 순서대로 실행한 뒤 요약을 출력하고 종료합니다.
각 케이스는 새 연결을 사용하며, timeout이나 통신 오류가 발생해도 다음 케이스를 진행합니다.
`delay`는 케이스 사이에만 적용됩니다.

설정 파일 없이 특정 타입만 실행:

```shell
java -jar target/http-mock-trouble-client.jar --mode cli --target-server-url http://localhost:8080/test --error-types invalid-header,content-length-short --delay 500 --max-timeout 3000
```

동일 타입을 반복 실행:

```shell
java -jar target/http-mock-trouble-client.jar --error-types invalid-header,invalid-header --delay 1000
```

옵션은 `--key value`와 `--key=value` 형식을 지원합니다. 동일 옵션을 두 번 지정하면 설정 오류입니다.
공백이 있는 설정 파일 경로나 URL은 따옴표로 감쌉니다.

## Swing 사용법

1. `--mode application`으로 실행합니다.
2. 상단에서 적용된 URL과 시간 설정을 확인합니다.
3. 목록에서 원하는 행을 선택한 뒤 **선택 실행**을 누르거나, **전체 실행**으로 설정 순서대로 실행합니다.
4. 행별 결과와 소요 시간, 하단 로그의 응답 요약을 확인합니다.
5. 실행을 취소하려면 **중지**를 누릅니다. 현재 소켓과 대기가 종료되고 남은 케이스는 실행하지 않습니다.

실행 중에는 재실행 버튼이 비활성화됩니다. 창을 닫으면 진행 중인 실행도 중지합니다.
**로그 지우기**는 화면의 로그를 비우며, 완료·중지·오류 알림은 화면 하단에 표시됩니다.
화면의 설정은 읽기 전용입니다. 설정을 바꾸려면 YAML 또는 CLI 옵션을 변경한 뒤 다시 실행합니다.

## 설정 파일과 옵션

설정 적용 순서는 **기본값 → YAML 파일 → CLI 옵션**입니다.
CLI의 `--error-types`는 YAML의 배열 전체를 교체하며, 타입의 순서와 중복을 유지합니다.
설정 파일을 생략하면 기본값으로 실행합니다.

[config/example.yml](config/example.yml)은 다음과 같습니다.

```yaml
mode: cli
target-server-url: http://localhost:8080/
error-types:
  - socket-close
  - invalid-initial-line
  - initial-line-wait
  - invalid-header
  - content-length-short
  - content-length-long
delay: 1000
max-timeout: 5000
connect-timeout: 3000
socket-close-delay: 1000
```

| YAML 키 | CLI 옵션 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `mode` | `--mode` | `cli` | `cli` 또는 `application` |
| `target-server-url` | `--target-server-url` | `http://localhost:8080` | 대상 HTTP/HTTPS URL |
| `error-types` | `--error-types` | 6개 전체 | YAML 배열 또는 CLI 쉼표 구분 목록 |
| `delay` | `--delay` | 1000 | 케이스 사이 대기 시간(ms), 0 이상 |
| `max-timeout` | `--max-timeout` | 5000 | 케이스 전체 시간 제한(ms), 1 이상 |
| `connect-timeout` | `--connect-timeout` | 3000 | TCP 연결 시간 제한(ms), 1 이상 |
| `socket-close-delay` | `--socket-close-delay` | 1000 | 연결 완료 후 클라이언트 종료까지 대기(ms), 0 이상 |
| — | `--config` | 없음 | UTF-8 YAML 파일 경로 |
| — | `--help`, `-h` | — | 도움말 출력 |

시간 값은 32비트 정수입니다. YAML에서는 따옴표 없는 숫자로 지정합니다.
빈 타입 배열, 알 수 없는 키/타입, 중복 YAML 키, null 값은 설정 오류로 처리합니다.
URL의 사용자 인증 정보(`user:password@host`)와 fragment(`#...`)는 지원하지 않습니다.

`max-timeout`은 DNS 조회, TCP 연결, TLS, 전송, 응답 대기를 포함한 케이스 전체 제한입니다.
TCP 연결에는 `connect-timeout`과 `max-timeout` 중 작은 값이 적용됩니다.
`socket-close`를 실행할 때는 `socket-close-delay`가 `max-timeout`보다 작아야 합니다.
연결 자체에도 시간이 필요하므로 전체 제한에 여유를 두어야 합니다.

## 오류 시나리오

| 타입 ID | 동작 | 종료 조건 |
| --- | --- | --- |
| `socket-close` | 연결 후 지정 시간 동안 유지하고 클라이언트에서 종료 | `socket-close-delay` 경과 |
| `invalid-initial-line` | `BROKEN_REQUEST_LINE\r\n\r\n` 전송 | 최종 응답 헤더 수신 또는 서버 종료 |
| `initial-line-wait` | `GET <path?query> HTTP/1.1\r\n`만 전송 | 서버가 소켓을 종료할 때까지 대기 |
| `invalid-header` | 정상 GET 첫 줄과 Host 뒤에 콜론 없는 헤더 전송 | 최종 응답 헤더 수신 또는 서버 종료 |
| `content-length-short` | Content-Length를 10으로 선언하고 `12345` 5바이트 전송 | 최종 응답 헤더 수신 또는 서버 종료 |
| `content-length-long` | Content-Length를 5로 선언하고 `1234567890` 10바이트 전송 | 최종 응답 헤더 수신 또는 서버 종료 |

모든 케이스에는 최대 시간 제한이 적용됩니다.
`initial-line-wait`는 헤더와 헤더 종료를 보내지 않는 불완전한 요청입니다. 응답을 받아도 서버가 소켓을 닫을 때까지 기다립니다.
Content-Length 케이스는 POST를 사용하며 송신 측을 half-close하지 않고 기다립니다.
선언 길이와 실제 본문은 위 표의 고정값을 사용합니다.

실제 응답은 서버 구현과 timeout 설정에 따라 다릅니다.
서버는 잘못된 요청을 끊거나 오류 응답을 보낼 수 있고, 초과 본문을 다음 요청으로 해석하거나 정상 응답을 보낼 수도 있습니다.

## 로그와 결과 해석

CLI 로그는 UTF-8이며 시간, 수준(`INFO`/`WARN`/`ERROR`), 타입, 실행 내역을 포함합니다.
응답 요약은 상태 줄, 헤더 및 함께 수신한 본문 일부를 최대 4096자로 표시합니다.
줄바꿈은 `\r`, `\n`으로 표시하며 timeout과 통신 오류에도 이미 받은 응답 일부를 출력합니다.

| 결과 | 의미 |
| --- | --- |
| `CLIENT_CLOSED` | 지정 시간 후 클라이언트에서 종료 |
| `SERVER_CLOSED` | 서버에서 연결 종료(EOF) |
| `ERROR_RESPONSE` | HTTP 4xx/5xx 오류 응답 관찰 |
| `RESPONSE_RECEIVED` | 그 외 응답 관찰 |
| `TIMEOUT` | 전체 시간 또는 TCP 연결 시간 제한 경과 |
| `NETWORK_ERROR` | 연결 거절, 연결 reset, TLS 등 통신 오류 |
| `RESPONSE_TOO_LARGE` | 최종 응답 헤더 종료 전에 64 KiB 수신 제한 초과 |
| `CANCELLED` | 실행 중지 |

CLI 종료 코드는 다음과 같습니다.

| 코드 | 의미 |
| --- | --- |
| `0` | 모든 케이스 관찰 완료. 4xx/5xx 응답 관찰도 포함 |
| `1` | timeout, 통신 오류, 수신 제한 초과 또는 실행 중단 발생 |
| `2` | 설정 또는 애플리케이션 시작 오류 |

`ERROR_RESPONSE`는 의도한 오류 응답을 관찰한 결과이므로 `WARN`으로 출력합니다.
코드 0은 클라이언트가 관찰을 마쳤다는 의미이며, 서버의 오류 처리 정책이 올바른지는 응답과 서버 로그로 판단합니다.
PowerShell에서는 실행 직후 `$LASTEXITCODE`로 CLI 종료 코드를 확인할 수 있습니다.

## 기술 설명

잘못된 HTTP 요청이 자동 보정되지 않도록 `Socket`/`SSLSocket`으로 HTTP/1.1 바이트를 직접 전송합니다.
URL의 기존 percent encoding은 유지하고 한글 경로와 query는 UTF-8 percent encoding으로 변환합니다.
각 케이스는 독립된 연결을 사용하며 자동 재시도하지 않습니다.

100/103 등 중간 응답은 건너뛰어 최종 응답을 기다립니다. 101 프로토콜 전환은 수신 결과로 종료합니다.
응답 케이스는 최종 응답 헤더가 완성되면 종료하므로 전체 본문을 다운로드하지 않습니다.
서버 종료 대기 케이스는 저장 버퍼가 가득 차도 계속 읽되 전체 timeout을 유지합니다.

CLI와 Swing은 동일한 실행기를 사용합니다. Swing의 네트워크 작업은 백그라운드에서 수행하고 화면 갱신은 EDT에서 처리합니다.
Swing 로그가 200,000자를 넘으면 오래된 로그를 제거해 최근 약 150,000자를 유지합니다.
YAML 로딩에는 SnakeYAML의 `SafeConstructor`를 사용합니다.

HTTPS는 JVM 기본 신뢰 저장소와 호스트명 검증을 사용합니다.
사설 인증서를 사용하는 서버는 신뢰할 인증서가 들어 있는 저장소를 JVM 옵션으로 지정할 수 있습니다.

```shell
java -Djavax.net.ssl.trustStore=server-truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -jar target/http-mock-trouble-client.jar --target-server-url https://localhost:8443/test --error-types invalid-header
```

필요한 저장소 비밀번호 등은 실행 환경에 맞게 추가 설정합니다.
구현 구조와 변경 이력은 [project.md](project.md)에 기록되어 있습니다.

## 문제 해결

| 증상 | 확인 사항 |
| --- | --- |
| `UnsupportedClassVersionError` 또는 `release version 21 not supported` | `java -version`, `mvn -version` 모두 JDK 21 이상을 사용하는지 확인 |
| `NETWORK_ERROR` / 연결 거절 | 서버 실행 여부, URL, 포트 및 네트워크 연결 확인 |
| `TIMEOUT` | 불완전한 요청에 대한 서버 대기시간과 `max-timeout` 비교 |
| TLS handshake/인증서 오류 | 인증서 신뢰 저장소와 URL 호스트명이 서버 인증서에 맞는지 확인 |
| Swing 실행 시 그래픽 화면 오류 | 데스크톱 환경에서 실행하거나 `--mode cli` 사용 |
| YAML/옵션 설정 오류 | 키 이름, 숫자 형식, 타입 배열 및 중복 키 확인 |

## 테스트

```shell
mvn clean verify
```

설정 검증, 실제 로컬 TCP 요청, 응답/timeout, CLI 종료 코드, Swing 선택 실행과 중지를 검증하는 테스트 20개가 포함되어 있습니다.
실제 대상 서버와 HTTPS 인증서에 대한 동작은 해당 환경에서 별도로 확인합니다.
