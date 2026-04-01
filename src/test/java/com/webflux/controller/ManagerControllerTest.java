package com.webflux.controller;

import com.webflux.domain.StreamSnapshot;
import com.webflux.domain.TikTokEvent;
import com.webflux.service.StreamManagerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * WebTestClient.bindToController() 를 사용해 Spring 컨텍스트 없이 컨트롤러만 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ManagerControllerTest {

    @Mock
    private StreamManagerService service;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new ManagerController(service)).build();
    }

    // ── GET /manager/stream ────────────────────────────────────────────────────

    @Test
    void streamReturnsSseEvents() {
        TikTokEvent snap = TikTokEvent.builder()
                .type("snapshot").uniqueId("s1").timestamp(Instant.now()).build();
        TikTokEvent chat = TikTokEvent.builder()
                .type("chat").uniqueId("s1").comment("hello").timestamp(Instant.now()).build();

        when(service.joinManager(isNull())).thenReturn(Flux.just(snap, chat));

        client.get().uri("/manager/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TikTokEvent.class)
                .hasSize(2)
                .value(events -> {
                    assertThat(events.get(0).getType()).isEqualTo("snapshot");
                    assertThat(events.get(1).getType()).isEqualTo("chat");
                });
    }

    @Test
    void streamFiltersByTypeQueryParam() {
        TikTokEvent chat = TikTokEvent.builder()
                .type("chat").uniqueId("s1").comment("hi").timestamp(Instant.now()).build();

        when(service.joinManager("chat")).thenReturn(Flux.just(chat));

        client.get().uri("/manager/stream?type=chat")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TikTokEvent.class)
                .hasSize(1)
                .value(events -> assertThat(events.get(0).getType()).isEqualTo("chat"));
    }

    // ── POST /manager/register/{uniqueId} ─────────────────────────────────────

    @Test
    void registerReturns200OnSuccess() {
        when(service.register("newStreamer")).thenReturn(Mono.empty());

        client.post().uri("/manager/register/newStreamer")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void registerReturns400OnDuplicate() {
        when(service.register("dup")).thenReturn(
                Mono.error(new IllegalArgumentException("이미 등록된 스트리머: dup")));

        client.post().uri("/manager/register/dup")
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ── DELETE /manager/register/{uniqueId} ───────────────────────────────────

    @Test
    void unregisterReturns204() {
        when(service.unregister("s1")).thenReturn(Mono.empty());

        client.delete().uri("/manager/register/s1")
                .exchange()
                .expectStatus().isNoContent();
    }

    // ── GET /manager/snapshot ─────────────────────────────────────────────────

    @Test
    void snapshotReturnsAllRegistered() {
        StreamSnapshot snap1 = StreamSnapshot.builder()
                .uniqueId("s1").live(true).viewerCount(100).createdAt(Instant.now()).build();
        StreamSnapshot snap2 = StreamSnapshot.builder()
                .uniqueId("s2").live(false).createdAt(Instant.now()).build();

        when(service.getSnapshots()).thenReturn(Flux.just(snap1, snap2));

        client.get().uri("/manager/snapshot")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StreamSnapshot.class)
                .hasSize(2)
                .value(snaps -> {
                    assertThat(snaps.get(0).getUniqueId()).isEqualTo("s1");
                    assertThat(snaps.get(0).isLive()).isTrue();
                    assertThat(snaps.get(1).isLive()).isFalse();
                });
    }

    @Test
    void snapshotReturnsEmptyWhenNoneRegistered() {
        when(service.getSnapshots()).thenReturn(Flux.empty());

        client.get().uri("/manager/snapshot")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(StreamSnapshot.class)
                .hasSize(0);
    }
}
