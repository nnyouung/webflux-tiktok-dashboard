# TikTok Live Dashboard

TikTok 라이브 스트림을 실시간으로 모니터링하는 대시보드.
**Spring WebFlux + SSE(Server-Sent Events)** 기반의 논블로킹 리액티브 아키텍처로 구현.

---

## 실행 방법

### 사전 조건
- Java 17+
- Euler Stream API Key ([eulerstream.com](https://eulerstream.com))

### 실행

프로젝트 루트에 `.env` 파일을 생성한다.

```
EULER_API_KEY=your_api_key_here
```

```bash
./gradlew bootRun
```

브라우저에서 `http://localhost:8080` 접속.

> API Key 없이도 실행은 되지만 Euler WebSocket 연결이 실패한다.  
> 이 경우 대시보드의 **Mock 시작** 버튼으로 랜덤 이벤트를 주입해 UI 동작을 확인할 수 있다.

참고:
- 모니터링할 스트리머 목록은 `src/main/resources/static/index.html`의 `STREAMERS` 배열에서 변경
- Euler API 설정은 `src/main/resources/application.yaml`의 `euler.api-key`, `euler.ws-url`

---

## 핵심 기술 포인트

### 1. Spring WebFlux — 논블로킹 I/O

```java
// 스레드를 점유하지 않고 비동기로 WebSocket 연결 + 재연결(backoff)
public Flux<TikTokEvent> connect(String uniqueId) {
    return Flux.defer(() -> connectSession(uniqueId))
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(3)));
}
```

- Netty 기반 이벤트 루프 — 스레드 1개로 다수 스트리머 동시 연결
- `Flux` 체인으로 WebSocket 수신 → 파싱 → 변환을 선언적으로 표현
- Euler 응답의 `WebcastChatMessage`/`WebcastLikeMessage` 같은 타입은 내부 이벤트 타입 `chat`/`like`로 정규화해 UI 랭킹 집계에 사용한다.

---

### 2. SSE — Cold + Hot 스트림 패턴

```java
public Flux<TikTokEvent> joinManager(String typeFilter) {
    // Cold: 구독 시점의 스냅샷 (늦게 접속해도 현재 상태 즉시 수신)
    Flux<TikTokEvent> cold = Flux.defer(() ->
        Flux.fromIterable(snapshots.values()).map(TikTokEvent::ofSnapshot)
    );

    // Hot: 이후 발생하는 실시간 이벤트 (이전 이벤트 리플레이/영속 저장 없음)
    return Flux.concat(cold, hotStream);
}
```

```java
// Controller — Flux를 그대로 반환하면 Spring이 SSE로 직렬화
// 실제 엔드포인트: GET /manager/stream (type 쿼리 파라미터로 필터 가능)
@RestController
@RequestMapping("/manager")
class ManagerController {
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<TikTokEvent> stream(@RequestParam(required = false) String type) {
        return service.joinManager(type);
    }
}
```

| 구분 | 역할 |
|------|------|
| **Cold** | 구독 시점의 “현재 상태” 스냅샷 전송 → 대시보드 초기 상태 복원 |
| **Hot**  | 구독 이후에 발생하는 이벤트를 실시간 푸시 (과거 이벤트 재생/누적은 하지 않음) |

---

### 3. 동적 스트림 병합 (Publisher of Publishers)

```
register("A") ──► sinkA.asFlux() ─┐
register("B") ──► sinkB.asFlux() ─┼─► streamRegistry ─► flatMap ─► hotStream
register("C") ──► sinkC.asFlux() ─┘
```

- `streamRegistry`: `Sinks<Flux<TikTokEvent>>` — 새 스트리머 등록 시 Flux를 동적으로 합류
- SSE 클라이언트가 이미 연결된 상태에서 스트리머를 추가해도 자동으로 이벤트 수신

---

## API

| Method | Endpoint | 설명 |
|--------|----------|------|
| `GET` | `/manager/stream` | SSE 스트림 (Cold + Hot), `?type=chat|gift|like|member|roomInfo|snapshot` 로 필터 가능 |
| `POST` | `/manager/register/{uniqueId}` | 스트리머 등록 |
| `DELETE` | `/manager/register/{uniqueId}` | 스트리머 해제 |
| `GET` | `/manager/snapshot` | 현재 스냅샷 목록 |
| `GET` | `/dev/mock?uniqueId={id}` | 랜덤 목 이벤트 주입 (개발용) |

---

## 아키텍처

```
Browser (SSE)
    │
    ▼
ManagerController  GET /manager/stream
    │   produces: text/event-stream
    ▼
StreamManagerService
    ├── Cold: snapshots (Flux.defer)
    └── Hot:  hotStream (publish().autoConnect(0))
                  ▲
         streamRegistry (Sinks<Flux>)
                  ▲
         sinksMap[uniqueId] ◄── EulerConnector (WebSocket)
                                     ▲
                              wss://ws.eulerstream.com
```

---

## 이벤트 타입

| type | 발생 시점 | 주요 필드 |
|------|----------|----------|
| `snapshot` | SSE 연결 시 초기값 | `live`, `viewerCount` |
| `like` | 좋아요 | `likeCount`(이번 이벤트), `totalLikes`(누적 합계) |
| `chat` | 채팅 | `comment` |
| `member` | 시청자 입장 | `viewerCount` |
| `roomInfo` | 방 정보 갱신 | `viewerCount`, `title` |
| `gift` | 선물 | `giftName`, `giftCount` |
