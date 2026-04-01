package com.webflux.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webflux.config.EulerProperties;
import com.webflux.domain.TikTokEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EulerConnectorTest {

    @Mock
    private WebSocketClient wsClient;

    @Mock
    private WebSocketSession session;

    private EulerConnector connector;

    @BeforeEach
    void setUp() {
        EulerProperties props = new EulerProperties();
        props.setApiKey("test-key");
        props.setWsUrl("wss://ws.eulerstream.com");

        connector = new EulerConnector(wsClient, props, new ObjectMapper());
    }

    @Test
    void shouldParseChatMessage() {
        String json = """
                {"messages":[{"type":"chat","data":{"user":{"userId":"u1","nickname":"User1"},"comment":"안녕!"}}]}
                """;

        givenSession(Flux.just(mockMsg(json)), CloseStatus.NORMAL);

        StepVerifier.create(connector.connect("streamer1").take(1))
                .assertNext(event -> {
                    assertThat(event.getType()).isEqualTo("chat");
                    assertThat(event.getUniqueId()).isEqualTo("streamer1");
                    assertThat(event.getNickname()).isEqualTo("User1");
                    assertThat(event.getComment()).isEqualTo("안녕!");
                })
                .verifyComplete();
    }

    @Test
    void shouldParseGiftMessage() {
        String json = """
                {"messages":[{"type":"gift","data":{"user":{"userId":"u2","nickname":"Gifter"},"giftName":"Rose","repeatCount":5}}]}
                """;

        givenSession(Flux.just(mockMsg(json)), CloseStatus.NORMAL);

        StepVerifier.create(connector.connect("streamer1").take(1))
                .assertNext(event -> {
                    assertThat(event.getType()).isEqualTo("gift");
                    assertThat(event.getGiftName()).isEqualTo("Rose");
                    assertThat(event.getGiftCount()).isEqualTo(5);
                })
                .verifyComplete();
    }

    @Test
    void shouldParseMultipleMessagesInOneFrame() {
        String json = """
                {"messages":[
                  {"type":"chat","data":{"user":{"userId":"u1","nickname":"A"},"comment":"hi"}},
                  {"type":"like","data":{"user":{"userId":"u2","nickname":"B"},"likeCount":10}}
                ]}
                """;

        givenSession(Flux.just(mockMsg(json)), CloseStatus.NORMAL);

        StepVerifier.create(connector.connect("streamer1").take(2))
                .assertNext(e -> assertThat(e.getType()).isEqualTo("chat"))
                .assertNext(e -> {
                    assertThat(e.getType()).isEqualTo("like");
                    assertThat(e.getLikeCount()).isEqualTo(10);
                })
                .verifyComplete();
    }

    @Test
    void shouldNormalizeWebcastChatMessageToChat() {
        String json = """
                {"messages":[{"type":"WebcastChatMessage","data":{"user":{"userId":"u3","nickname":"Nick3"},"comment":"hello"}}]}
                """;

        givenSession(Flux.just(mockMsg(json)), CloseStatus.NORMAL);

        StepVerifier.create(connector.connect("streamer1").take(1))
                .assertNext(event -> {
                    assertThat(event.getType()).isEqualTo("chat");
                    assertThat(event.getUserId()).isEqualTo("u3");
                    assertThat(event.getNickname()).isEqualTo("Nick3");
                    assertThat(event.getComment()).isEqualTo("hello");
                })
                .verifyComplete();
    }

    @Test
    void shouldNormalizeWebcastLikeMessageToLike() {
        String json = """
                {"messages":[{"type":"WebcastLikeMessage","data":{"user":{"userId":"u4","nickname":"Nick4"},"likeCount":7}}]}
                """;

        givenSession(Flux.just(mockMsg(json)), CloseStatus.NORMAL);

        StepVerifier.create(connector.connect("streamer1").take(1))
                .assertNext(event -> {
                    assertThat(event.getType()).isEqualTo("like");
                    assertThat(event.getUserId()).isEqualTo("u4");
                    assertThat(event.getNickname()).isEqualTo("Nick4");
                    assertThat(event.getLikeCount()).isEqualTo(7);
                })
                .verifyComplete();
    }

    @Test
    void shouldNormalizePinnedChatToChat() {
        String json = """
                {"messages":[{"type":"WebcastRoomPinMessage","data":{"chatMessage":{"method":"WebcastChatMessage","user":{"userId":"u5","nickname":"Nick5"},"comment":"pinned"}}}]}
                """;

        givenSession(Flux.just(mockMsg(json)), CloseStatus.NORMAL);

        StepVerifier.create(connector.connect("streamer1").take(1))
                .assertNext(event -> {
                    assertThat(event.getType()).isEqualTo("chat");
                    assertThat(event.getUserId()).isEqualTo("u5");
                    assertThat(event.getNickname()).isEqualTo("Nick5");
                    assertThat(event.getComment()).isEqualTo("pinned");
                })
                .verifyComplete();
    }

    @Test
    void shouldEmitNotLiveSnapshotOn4404AndComplete() {
        // 4404: 스트리머 오프라인 → snapshot 이벤트 emit 후 완료 (재연결 없음)
        givenSession(Flux.empty(), new CloseStatus(EulerConnector.NOT_LIVE_CODE));

        StepVerifier.create(connector.connect("offline1"))
                .assertNext(event -> {
                    assertThat(event.getType()).isEqualTo("snapshot");
                    assertThat(event.getUniqueId()).isEqualTo("offline1");
                })
                .verifyComplete();
    }

    @Test
    void shouldIgnoreEmptyMessagesArray() {
        String json = """
                {"messages":[]}
                """;

        givenSession(Flux.just(mockMsg(json)), CloseStatus.NORMAL);

        // 메시지가 없으면 아무것도 emit 되지 않고 완료
        StepVerifier.create(connector.connect("streamer1"))
                .verifyComplete();
    }

    @Test
    void shouldIgnoreMalformedJson() {
        String json = "NOT_JSON";

        givenSession(Flux.just(mockMsg(json)), CloseStatus.NORMAL);

        // 파싱 실패 시 해당 프레임은 무시하고 완료
        StepVerifier.create(connector.connect("streamer1"))
                .verifyComplete();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void givenSession(Flux<WebSocketMessage> messages, CloseStatus closeStatus) {
        when(session.receive()).thenReturn(messages);
        when(session.closeStatus()).thenReturn(Mono.just(closeStatus));
        when(wsClient.execute(any(URI.class), any())).thenAnswer(inv -> {
            org.springframework.web.reactive.socket.WebSocketHandler handler = inv.getArgument(1);
            return handler.handle(session);
        });
    }

    private WebSocketMessage mockMsg(String text) {
        WebSocketMessage msg = mock(WebSocketMessage.class);
        when(msg.getPayloadAsText()).thenReturn(text);
        return msg;
    }
}
