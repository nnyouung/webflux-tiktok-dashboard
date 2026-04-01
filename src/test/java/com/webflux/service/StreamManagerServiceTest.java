package com.webflux.service;

import com.webflux.connector.EulerConnector;
import com.webflux.domain.StreamSnapshot;
import com.webflux.domain.TikTokEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamManagerServiceTest {

    @Mock
    private EulerConnector connector;

    private StreamManagerService service;

    @BeforeEach
    void setUp() {
        service = new StreamManagerService(connector);
        service.init();
    }

    @Test
    void coldSnapshotComesBeforeHotEvents() {
        // Given: streamer1 이 등록돼 있고 snapshot 상태가 설정됨
        Sinks.Many<TikTokEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        when(connector.connect("streamer1")).thenReturn(sink.asFlux());
        service.register("streamer1").block();

        // snapshot 에 roomInfo 이벤트 반영 (viewerCount=100)
        sink.tryEmitNext(chatEvent("streamer1", "before-subscribe"));

        // When: 구독 시작
        Flux<TikTokEvent> stream = service.joinManager(null);

        // Then: Cold(snapshot) 먼저, 이후 Hot 이벤트
        StepVerifier.create(stream.take(2))
                .assertNext(e -> {
                    assertThat(e.getType()).isEqualTo("snapshot");
                    assertThat(e.getUniqueId()).isEqualTo("streamer1");
                })
                .then(() -> sink.tryEmitNext(chatEvent("streamer1", "after-subscribe")))
                .assertNext(e -> {
                    assertThat(e.getType()).isEqualTo("chat");
                    assertThat(e.getComment()).isEqualTo("after-subscribe");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(3));
    }

    @Test
    void coldSnapshotCapturesStateAtSubscribeTime() {
        // Given
        when(connector.connect("s1")).thenReturn(Flux.never());
        when(connector.connect("s2")).thenReturn(Flux.never());
        service.register("s1").block();
        service.register("s2").block();

        // When: 구독
        Flux<TikTokEvent> cold = service.joinManager(null).take(2);

        StepVerifier.create(cold)
                .assertNext(e -> assertThat(e.getType()).isEqualTo("snapshot"))
                .assertNext(e -> assertThat(e.getType()).isEqualTo("snapshot"))
                .verifyComplete();
    }

    @Test
    void typeFilterApplied() {
        Sinks.Many<TikTokEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        when(connector.connect("s1")).thenReturn(sink.asFlux());
        service.register("s1").block();

        StepVerifier.create(service.joinManager("chat").skip(1).take(1))
                .then(() -> {
                    sink.tryEmitNext(giftEvent("s1"));
                    sink.tryEmitNext(chatEvent("s1", "hello"));
                })
                .assertNext(e -> {
                    assertThat(e.getType()).isEqualTo("chat");
                    assertThat(e.getComment()).isEqualTo("hello");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(3));
    }

    @Test
    void duplicateRegisterReturnsError() {
        when(connector.connect("dup")).thenReturn(Flux.never());
        service.register("dup").block();

        StepVerifier.create(service.register("dup"))
                .expectErrorMatches(e ->
                        e instanceof IllegalArgumentException &&
                        e.getMessage().contains("dup"))
                .verify();
    }

    @Test
    void unregisterRemovesStreamer() {
        when(connector.connect("s1")).thenReturn(Flux.never());
        service.register("s1").block();

        service.unregister("s1").block();

        StepVerifier.create(service.getSnapshots())
                .verifyComplete(); // 스냅샷이 비어 있어야 함
    }

    @Test
    void snapshotUpdatedOnRoomInfo() {
        Sinks.Many<TikTokEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        when(connector.connect("s1")).thenReturn(sink.asFlux());
        service.register("s1").block();

        TikTokEvent roomInfo = TikTokEvent.builder()
                .type("roomInfo")
                .uniqueId("s1")
                .viewerCount(500)
                .followerCount(10000L)
                .title("Live Now!")
                .build();
        sink.tryEmitNext(roomInfo);

        // 비동기 처리 대기
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        AtomicReference<StreamSnapshot> captured = new AtomicReference<>();
        service.getSnapshots().subscribe(captured::set);

        assertThat(captured.get().isLive()).isTrue();
        assertThat(captured.get().getViewerCount()).isEqualTo(500);
    }

    @Test
    void notLiveSnapshotEventMarksOffline() {
        Sinks.Many<TikTokEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        when(connector.connect("offline")).thenReturn(sink.asFlux());
        service.register("offline").block();

        // 4404 에 의해 EulerConnector 가 emit 하는 not-live snapshot
        TikTokEvent notLive = TikTokEvent.builder()
                .type("snapshot")
                .uniqueId("offline")
                .build();
        sink.tryEmitNext(notLive);

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        AtomicReference<StreamSnapshot> captured = new AtomicReference<>();
        service.getSnapshots().subscribe(captured::set);

        assertThat(captured.get().isLive()).isFalse();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private TikTokEvent chatEvent(String uniqueId, String comment) {
        return TikTokEvent.builder()
                .type("chat")
                .uniqueId(uniqueId)
                .userId("u1")
                .nickname("Tester")
                .comment(comment)
                .build();
    }

    private TikTokEvent giftEvent(String uniqueId) {
        return TikTokEvent.builder()
                .type("gift")
                .uniqueId(uniqueId)
                .giftName("Rose")
                .giftCount(1)
                .build();
    }
}
