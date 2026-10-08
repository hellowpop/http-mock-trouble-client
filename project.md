# HTTP Mock Trouble Client

## 목적과 설계

Java 21 실행 JAR로 HTTP 서버의 잘못된 요청 처리와 소켓 종료 동작을 관찰한다.
CLI는 지정한 순서대로 모든 케이스를 실행하고 종료하며 Swing은 설정 표시, 개별/전체 실행, 로그/결과 알림을 제공한다.
고수준 HTTP 클라이언트는 잘못된 요청을 보정하므로 raw Socket/SSLSocket을 사용한다.
Maven + SnakeYAML을 선택한다. Spring Boot는 이 도구의 CLI/Swing 구조에는 불필요하고 자체 YAML 파서는 표준 형식 지원과 검증 유지 비용이 크다.

## 구현 계획

직접 구현하고 각 단계의 테스트를 먼저 실행한다. 기술 문서는 이 파일에서 관리한다.

- [x] 설정: `ClientConfig`, `ConfigLoader`, `ErrorType`. YAML → CLI 우선순위, 순서 보존, 기본값 및 잘못된 입력 검증 테스트 후 구현.
- [x] 소켓 실행: `ScenarioRunner`, `CaseResult`, `RunEvent`. 로컬 TCP 서버로 6개 요청 바이트, EOF, 오류 응답, 무응답/부분 응답/지속 응답의 전체 deadline 테스트 후 구현.
- [x] 진입점/화면: `Main`, `ClientWindow`, `ClientPanel`, `SuiteRunner`. 순차 실행, delay, CLI 종료 코드, Swing 개별/전체/중지와 로그 표시. EDT에서 네트워크를 실행하지 않는다.
- [x] 검증: Java 21 `mvn clean verify`, 실행 JAR 도움말/설정 오류/로컬 서버 smoke test. 결과와 구조 변경 기록.

검토 초점: YAML 중복/알 수 없는 키, 빈 타입 목록, CLI에 의한 잘못된 값 보정, 응답 일부만 보내는 서버, 읽기마다 timeout을 갱신해 무한 대기하는 오류.

## 기본 동작 결정

- 기본 실행타입 `cli`, URL `http://localhost:8080`, 타입 전체, delay 1000 ms, max-timeout 5000 ms, connect-timeout 3000 ms, socket-close-delay 1000 ms.
- 모든 케이스는 연결/DNS/TLS/쓰기/읽기 포함 전체 max-timeout을 적용한다. 서버 종료 대기도 무한 대기하지 않는다.
- 정상 initial line 케이스는 요청 첫 줄과 CRLF만 보내고 헤더 종료를 보내지 않는다. 서버가 연결을 종료할 때까지 기다린다.
- content length 부족: 10바이트 선언/5바이트 전송. 초과: 5바이트 선언/10바이트 전송. 송신 측을 half-close하지 않아 서버의 실제 대기 동작을 유지한다.
- 케이스마다 새 연결. 실패 후 다음 케이스 진행. delay는 케이스 사이에만 적용한다.
- 서버의 4xx/5xx는 관찰 결과이며 CLI 실패 코드가 아니다. timeout/네트워크 오류는 코드 1, 설정 오류는 코드 2.

## 프로젝트 구조

Git 저장소: [hellowpop/http-mock-trouble-client](https://github.com/hellowpop/http-mock-trouble-client).
원격 이름은 `origin`, 최초 업로드 브랜치는 로컬에서 초기화된 `master`를 사용한다.
빌드 산출물(`target/`)과 IDE 설정(`.idea/`, `*.iml`)은 Git에서 제외한다.

```text
pom.xml                            Java 21 컴파일 및 의존성 포함 실행 JAR
config/example.yml                 설정 예제
src/main/java/io/github/httpmock/
  Main.java                        도움말, 설정, CLI/Swing 진입점
  ConfigLoader.java                안전한 YAML 로딩 + CLI 덮어쓰기
  ClientConfig.java                설정 검증, URL/Host/요청 경로
  ErrorType.java                   6개 타입 ID와 설명
  ScenarioRunner.java              raw TCP/TLS, 케이스 deadline, 응답 요약
  SuiteRunner.java                 순차 실행, 케이스 사이 delay, 중지
  CaseResult.java                  결과와 실패 판단
  RunEvent.java                    시간/수준/타입 포함 로그
  ClientWindow.java                Swing 프레임과 창 닫기 처리
  ClientPanel.java                 설정/목록/로그/버튼/결과 알림
src/test/java/io/github/httpmock/   설정, 실제 TCP, CLI, Swing 테스트
target/http-mock-trouble-client.jar 빌드 결과
```

## 빌드 및 실행

JDK 21 이상과 Maven 3.9가 필요하다. 컴파일 target은 Java 21이다.
현재 PC의 기본 Java는 17이므로 PowerShell에서 설치된 JDK 21을 선택한 후 실행한다.

```powershell
$env:JAVA_HOME = 'D:\01.app\java\jdk-21.0.3'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn clean verify
java -jar target/http-mock-trouble-client.jar --help
java -jar target/http-mock-trouble-client.jar --config config/example.yml
java -jar target/http-mock-trouble-client.jar --config config/example.yml --mode application
```

CLI만으로 특정 케이스를 실행하는 예:

```powershell
java -jar target/http-mock-trouble-client.jar --mode cli --target-server-url http://localhost:8080/test --error-types invalid-header,content-length-short --delay 500 --max-timeout 3000
```

파일 없이 실행하면 기본 설정을 사용한다. `--key=value`와 `--key value`를 모두 지원한다.
동일 CLI 옵션을 반복하면 오류를 출력한다. 옵션마다 YAML의 해당 값 전체를 덮어쓰며 error-types는 배열 전체를 교체한다.
타입 배열의 순서와 중복을 유지하므로 동일 타입을 반복 테스트할 수 있다.

## 설정

| YAML 키 / CLI 옵션 | 기본값 | 조건 |
| --- | --- | --- |
| `mode` / `--mode` | `cli` | `cli`, `application` |
| `target-server-url` / `--target-server-url` | `http://localhost:8080` | HTTP/HTTPS URL. 인증 사용자정보와 fragment는 지원하지 않음 |
| `error-types` / `--error-types` | 6개 전체 | YAML 배열 / CLI 쉼표 구분. 빈 배열 불가 |
| `delay` / `--delay` | 1000 | 케이스 간 대기 ms, 0 이상 |
| `max-timeout` / `--max-timeout` | 5000 | 케이스 전체 제한 ms, 1 이상 |
| `connect-timeout` / `--connect-timeout` | 3000 | TCP 연결 제한 ms, 1 이상. max-timeout 이내로 적용 |
| `socket-close-delay` / `--socket-close-delay` | 1000 | 연결 완료 후 종료까지 ms, 0 이상. 해당 케이스가 있으면 max-timeout 미만 |
| `--config` | 없음 | UTF-8 YAML 파일 경로 |

시간은 32비트 정수이며 YAML에서는 따옴표 없는 숫자를 사용한다. 알 수 없는 설정 키, 중복 YAML 키, 잘못된 타입, null 값은 오류 처리한다.
HTTPS는 JVM 기본 신뢰 저장소와 서버 호스트명 검증을 사용한다. 사설 인증서는 JVM trustStore 옵션으로 설정한다.

## 시나리오와 결과

| 타입 ID | 전송 내용 | 정상 관찰 종료 조건 |
| --- | --- | --- |
| `socket-close` | TCP/TLS 연결만 수행 | socket-close-delay 후 클라이언트 종료 |
| `invalid-initial-line` | `BROKEN_REQUEST_LINE\r\n\r\n` | 응답 헤더 수신 또는 서버 종료 |
| `initial-line-wait` | `GET <path?query> HTTP/1.1\r\n`만 전송 | 서버 종료. 응답이 있어도 종료까지 대기 |
| `invalid-header` | 정상 GET 첫 줄 + Host + 콜론 없는 헤더 | 응답 헤더 수신 또는 서버 종료 |
| `content-length-short` | POST, Content-Length 10 + `12345` | 응답 헤더 수신 또는 서버 종료 |
| `content-length-long` | POST, Content-Length 5 + `1234567890` | 응답 헤더 수신 또는 서버 종료 |

HTTP/1.1을 사용하며 path와 query는 기존 percent encoding을 유지하고 한글 등 Unicode는 UTF-8 percent encoding으로 변환한다. 소켓을 매번 새로 연결하며 재시도하지 않는다.
서버는 초과 본문을 다음 요청으로 해석하거나 정상 응답을 보낼 수도 있으므로 반드시 오류 응답이 발생한다고 단정하지 않는다.

응답은 상태 줄과 헤더 및 같은 read에서 받은 본문 일부를 최대 4096자로 요약한다.
최종 응답의 헤더 종료(`CRLF CRLF`)를 수신하면 응답 케이스는 종료하며 전체 본문 다운로드는 하지 않는다.
100/103 등 중간 응답은 건너뛰어 최종 응답을 기다린다. 101 프로토콜 전환은 수신 결과로 종료한다.
분할 수신을 지원하고 헤더 수신 버퍼는 64 KiB까지 제한한다. 제한 초과는 `RESPONSE_TOO_LARGE`로 종료한다.
서버 종료 대기는 저장 버퍼가 가득 차도 읽기를 계속하며 max-timeout은 계속 적용된다.
Timeout/통신 오류에도 이미 받은 응답 일부를 함께 출력한다. 로그의 CR/LF는 `\r`, `\n`으로 표시한다.

| 결과 | 의미 | CLI 종료에 미치는 영향 |
| --- | --- | --- |
| `CLIENT_CLOSED` | 예정된 클라이언트 소켓 종료 | 코드 0 |
| `SERVER_CLOSED` | 서버 EOF, initial-line-wait의 종료 조건 | 코드 0 |
| `ERROR_RESPONSE` | HTTP 4xx/5xx 응답 관찰 | 코드 0, WARN 로그 |
| `RESPONSE_RECEIVED` | 그 외 응답 관찰 | 코드 0 |
| `TIMEOUT` | max-timeout 또는 connect-timeout 경과 | 코드 1, ERROR 로그 |
| `NETWORK_ERROR` | 연결 거절, reset, TLS 등 통신 오류 | 코드 1, ERROR 로그 |
| `RESPONSE_TOO_LARGE` | 헤더 종료 전에 수신 제한 초과 | 코드 1 |
| `CANCELLED` | Swing 중지 또는 실행 스레드 중단 | 코드 1에 해당하는 실패 결과 |

CLI는 모든 케이스가 끝난 뒤 UTF-8 로그와 요약을 출력한다. 하나라도 실행 실패가 있으면 1, 설정/시작 오류면 2를 반환한다.
Swing은 선택 행 1개 또는 전체 행을 실행한다. 실행 중 재실행을 막고 중지하면 현재 소켓/대기와 남은 실행을 종료한다.
로그와 행별 결과를 EDT에서 갱신하고 마지막 완료/중지/오류 요약을 하단에 표시한다. 창 닫기도 실행을 중지한다.
화면의 로그는 200,000자를 넘으면 오래된 내용을 제거해 최근 약 150,000자를 유지한다.

## 검증 기록

- 설정 테스트 5개: CLI/YAML 우선순위, 배열 순서/중복, 잘못된 값, 중복/알 수 없는 YAML 키.
- 소켓 테스트 9개: 6개 실제 전송 내용, 오류 응답/헤더 분할, EOF, 무응답/지속 부분 응답 deadline, 중지 시 소켓 닫기, 중간 응답 뒤 최종 오류, 한글 URL 바이트 보존.
- CLI 테스트 4개: 도움말/설정 종료 코드, timeout 후 계속 실행, delay, 오류 응답은 관찰 성공, 중지한 suite.
- Swing 테스트 2개: 선택 행 실행/로그/버튼 상태, 실행 중 중지와 결과 표시.
- Java 21에서 `mvn test`: 20개 통과. 실제 대상 서버의 응답 정책은 서버 설정에 따라 달라진다.
- 독립 코드 리뷰에서 중간 응답 조기 종료와 한글 URL 변형을 재현했고, 두 회귀 테스트의 실패 → 수정 → 전체 통과를 확인했다.
- 최종 `mvn clean verify`: 20개 테스트 통과, 의존성을 포함한 실행 JAR 생성, shade 경고 없음.
- 생성된 JAR를 별도 Java 프로세스로 실행: YAML + CLI 덮어쓰기, 6개 요청 바이트/응답 요약, 종료 코드 0 확인. 잘못된 설정의 종료 코드 2도 확인.
- Swing 전체 실행 smoke: 6개 행의 결과와 완료 알림 확인. 화면을 `target/swing-preview.png`로 렌더링하여 설정/목록/로그/버튼 배치 확인. 선택 실행/중지는 별도 자동 테스트로 검증.
- 테스트는 로컬 TCP 서버를 사용했다. 실제 대상 서버 및 HTTPS 인증서와의 통합 검증은 수행하지 않았다.

구현 API 참고: [Java 21 Socket](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/Socket.html), [SnakeYAML LoaderOptions](https://github.com/snakeyaml/snakeyaml/blob/master/src/main/java/org/yaml/snakeyaml/LoaderOptions.java).

## 변경 이력

- 2026-10-08: 빈 프로젝트를 확인하고 Java 21 Maven 구조 및 공통 실행기 설계 수립. Git 저장소는 아직 초기화되어 있지 않음.
- 2026-10-08: 설정/소켓/순차 실행/Swing을 책임별 10개 클래스로 구성. YAML 예제, 실행 JAR 설정 및 테스트 18개 추가.
- 2026-10-08: 리뷰 후 최종 HTTP 응답 처리와 Unicode URL percent encoding 수정, 회귀 테스트 2개 추가. CLI 출력 인코딩을 UTF-8로 고정.
- 2026-10-09: 실행 JAR 및 Swing 전체 실행 smoke 완료. 최종 구조, 설정/실행 방법과 검증 결과 기록.
- 2026-10-09: 제공된 GitHub 저장소를 `origin`으로 연결. 원격이 비어 있음을 확인하고 `master` 최초 커밋/푸시를 위해 기술 문서를 갱신.
