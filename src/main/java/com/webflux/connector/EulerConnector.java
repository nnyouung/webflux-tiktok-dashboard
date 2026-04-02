package com.webflux.connector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webflux.config.EulerProperties;
import com.webflux.domain.TikTokEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Euler Stream WebSocket API에 연결해 TikTokEvent 스트림을 반환한다.
 * <p>
 * - 4404(NOT_LIVE) 수신 시: isLive=false snapshot 이벤트를 emit 후 Flux 완료 (재연결 없음)
 * - 그 외 에러: 3초 베이스 exponential backoff으로 무한 재연결
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EulerConnector {

    static final int NOT_LIVE_CODE = 4404;

    private final WebSocketClient wsClient;
    private final EulerProperties props;
    private final ObjectMapper objectMapper;

    /**
     * 주어진 스트리머 uniqueId에 대한 이벤트 스트림을 반환한다.
     * 4404를 제외한 모든 에러에서 backoff 재연결을 수행한다.
     */
    public Flux<TikTokEvent> connect(String uniqueId) {
        return Flux.defer(() -> connectSession(uniqueId))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(3))
                        .doBeforeRetry(signal -> {
                            long attempt = signal.totalRetries() + 1;
                            String msg = signal.failure() != null ? signal.failure().getMessage() : "unknown";

                            // 연결 장애가 지속되면 warn 스팸이 심해져서, 초반/주기만 WARN으로 남긴다.
                            // (예: 프레임 제한, 일시적 네트워크 장애)
                            if (attempt <= 3 || attempt % 20 == 0) {
                                log.warn("[{}] WS 재연결 시도 #{}: {}", uniqueId, attempt, msg);
                            } else {
                                log.debug("[{}] WS 재연결 시도 #{}: {}", uniqueId, attempt, msg);
                            }
                        })
                );
    }

    // ── 내부 ───────────────────────────────────────────────────────────────────

    /**
     * 단일 WebSocket 세션에 대한 Flux.
     * 세션이 닫히면 닫힘 코드에 따라 완료(4404) 또는 에러(그 외)로 종료된다.
     */
    private Flux<TikTokEvent> connectSession(String uniqueId) {
        URI uri = buildUri(uniqueId);
        // unicast: 이 Flux 의 구독자는 하나(StreamManagerService)
        Sinks.Many<TikTokEvent> bridge = Sinks.many().unicast().onBackpressureBuffer();

        wsClient.execute(uri, session -> {
            Flux<TikTokEvent> messages = session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .flatMap(json -> parseMessages(json, uniqueId));

            // 메시지를 모두 받은 뒤 close status 확인
            Mono<TikTokEvent> closeEvent = session.closeStatus()
                    .defaultIfEmpty(new CloseStatus(1006))
                    .flatMap(status -> {
                        if (status.getCode() == NOT_LIVE_CODE) {
                            log.info("[{}] 오프라인 (4404)", uniqueId);
                            return Mono.just(buildNotLiveSnapshot(uniqueId));
                        }
                        return Mono.error(new RuntimeException(
                                "[" + uniqueId + "] WS 종료: " + status.getCode()));
                    });

            return messages
                    .concatWith(closeEvent)
                    .doOnNext(bridge::tryEmitNext)
                    .doOnError(bridge::tryEmitError)
                    .doOnComplete(bridge::tryEmitComplete)
                    .then();
        })
        .doOnError(e -> bridge.tryEmitError(e))   // 세션 핸들러 진입 전 연결 실패
        .subscribe(
                null,
                e -> log.debug("[{}] WS execute 에러: {}", uniqueId, e.getMessage())
        );

        return bridge.asFlux();
    }

    private Flux<TikTokEvent> parseMessages(String json, String streamerId) {
        logRawFrame(streamerId, json);
        EulerResponse response;
        try {
            response = objectMapper.readValue(json, EulerResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("[{}] JSON 파싱 실패: {}", streamerId, e.getMessage());
            return Flux.empty();
        }

        List<EulerMessage> messages = response.getMessages();
        if (messages == null || messages.isEmpty()) {
            return Flux.empty();
        }

        return Flux.fromIterable(messages)
                .map(msg -> toEvent(msg, streamerId));
    }

    private void logRawFrame(String streamerId, String json) {
        if (log.isTraceEnabled()) {
            log.trace("[{}] RAW JSON: {}", streamerId, json);
            return;
        }

        if (!log.isDebugEnabled()) {
            return;
        }

        // DEBUG에서는 너무 긴 프레임이 로그를 오염시키므로 길이/앞부분만 남긴다.
        int len = json != null ? json.length() : 0;
        int max = 512;
        String head = json == null ? "null" : json.substring(0, Math.min(len, max));
        String suffix = (len > max) ? "...(truncated, len=" + len + ")" : "(len=" + len + ")";
        log.debug("[{}] RAW JSON(head): {} {}", streamerId, head, suffix);
    }

    /**
     * Euler 실제 JSON 구조: { "type": "...", "data": { ... } }
     * data 내부는 type마다 다름 → JsonNode로 직접 파싱.
     *
     * roomInfo:  data.roomInfo.currentViewers / data.user.followers / data.roomInfo.title
     * like:      data.user / data.likeCount
     * chat:      data.user / data.comment
     * member:    data.user / data.viewerCount
     * gift:      data.user / data.giftName / data.repeatCount
     */
    private TikTokEvent toEvent(EulerMessage msg, String streamerId) {
        com.fasterxml.jackson.databind.JsonNode d =
                msg.getData() != null ? msg.getData()
                        : objectMapper.createObjectNode();

        return switch (msg.getType()) {
            case "roomInfo" -> {
                com.fasterxml.jackson.databind.JsonNode ri = d.path("roomInfo");
                yield TikTokEvent.builder()
                        .type("roomInfo")
                        .uniqueId(streamerId)
                        .viewerCount(ri.path("currentViewers").asInt(0))
                        .followerCount(d.path("user").path("followers").asLong(0))
                        .title(ri.path("title").asText(""))
                        .timestamp(Instant.now())
                        .build();
            }
            // Newer TikTok webcast schema (e.g. "WebcastChatMessage") → normalize to our internal types.
            case "WebcastChatMessage" -> buildChatEvent(streamerId, d.path("user"), d.path("comment").asText(""));
            case "WebcastLikeMessage" -> buildLikeEvent(streamerId, d.path("user"), extractLikeCount(d), extractTotalLikes(d));
            case "WebcastRoomPinMessage" -> {
                // Pin message can contain an embedded WebcastChatMessage under data.chatMessage.
                com.fasterxml.jackson.databind.JsonNode chat = d.path("chatMessage");
                if (!chat.isMissingNode() && !chat.isNull()) {
                    String comment = chat.path("comment").asText("");
                    yield buildChatEvent(streamerId, chat.path("user"), comment);
                }
                yield TikTokEvent.builder()
                        .type(msg.getType())
                        .uniqueId(streamerId)
                        .timestamp(Instant.now())
                        .build();
            }
            case "like" -> TikTokEvent.builder()
                    .type("like")
                    .uniqueId(streamerId)
                    .userId(d.path("user").path("userId").asText(""))
                    .nickname(d.path("user").path("nickname").asText(""))
                    .likeCount(d.path("likeCount").asInt(1))
                    .totalLikes(extractTotalLikes(d))
                    .timestamp(Instant.now())
                    .build();
            case "chat" -> TikTokEvent.builder()
                    .type("chat")
                    .uniqueId(streamerId)
                    .userId(d.path("user").path("userId").asText(""))
                    .nickname(d.path("user").path("nickname").asText(""))
                    .comment(d.path("comment").asText(""))
                    .timestamp(Instant.now())
                    .build();
            case "member" -> TikTokEvent.builder()
                    .type("member")
                    .uniqueId(streamerId)
                    .userId(d.path("user").path("userId").asText(""))
                    .nickname(d.path("user").path("nickname").asText(""))
                    .viewerCount(d.path("viewerCount").asInt(0))
                    .timestamp(Instant.now())
                    .build();
            case "gift" -> TikTokEvent.builder()
                    .type("gift")
                    .uniqueId(streamerId)
                    .userId(d.path("user").path("userId").asText(""))
                    .nickname(d.path("user").path("nickname").asText(""))
                    .giftName(d.path("giftName").asText(""))
                    .giftCount(d.path("repeatCount").asInt(1))
                    .timestamp(Instant.now())
                    .build();
            default -> TikTokEvent.builder()
                    .type(msg.getType())
                    .uniqueId(streamerId)
                    .timestamp(Instant.now())
                    .build();
        };
    }

    private TikTokEvent buildChatEvent(String streamerId,
                                      com.fasterxml.jackson.databind.JsonNode userNode,
                                      String comment) {
        return TikTokEvent.builder()
                .type("chat")
                .uniqueId(streamerId)
                .userId(userNode.path("userId").asText(""))
                .nickname(userNode.path("nickname").asText(""))
                .comment(comment)
                .timestamp(Instant.now())
                .build();
    }

    private TikTokEvent buildLikeEvent(String streamerId,
                                      com.fasterxml.jackson.databind.JsonNode userNode,
                                      int likeCount,
                                      long totalLikes) {
        return TikTokEvent.builder()
                .type("like")
                .uniqueId(streamerId)
                .userId(userNode.path("userId").asText(""))
                .nickname(userNode.path("nickname").asText(""))
                .likeCount(likeCount)
                .totalLikes(totalLikes)
                .timestamp(Instant.now())
                .build();
    }

    private int extractLikeCount(com.fasterxml.jackson.databind.JsonNode d) {
        int likeCount = d.path("likeCount").asInt(0);
        if (likeCount <= 0) likeCount = d.path("count").asInt(0);
        if (likeCount <= 0) likeCount = d.path("diggCount").asInt(0);
        return likeCount > 0 ? likeCount : 1;
    }

    private long extractTotalLikes(com.fasterxml.jackson.databind.JsonNode d) {
        long total = d.path("totalLikes").asLong(0);
        if (total <= 0) total = d.path("totalLikeCount").asLong(0);
        return total;
    }

    private TikTokEvent buildNotLiveSnapshot(String uniqueId) {
        return TikTokEvent.builder()
                .type("snapshot")
                .uniqueId(uniqueId)
                .timestamp(Instant.now())
                .build();
    }

    private URI buildUri(String uniqueId) {
        return URI.create(props.getWsUrl()
                + "?uniqueId=" + uniqueId
                + "&apiKey=" + props.getApiKey());
    }
}
