package com.webflux.service;

import com.webflux.connector.EulerConnector;
import com.webflux.domain.StreamSnapshot;
import com.webflux.domain.TikTokEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamManagerService {

    private final EulerConnector connector;

    // 스트리머별 이벤트 Sink
    private final ConcurrentHashMap<String, Sinks.Many<TikTokEvent>> sinksMap = new ConcurrentHashMap<>();

    // 스트리머별 현재 상태 스냅샷
    private final ConcurrentHashMap<String, StreamSnapshot> snapshots = new ConcurrentHashMap<>();

    // 스트리머별 Euler WS 구독 핸들
    private final ConcurrentHashMap<String, Disposable> connections = new ConcurrentHashMap<>();

    /**
     * 새 스트리머 Flux를 동적으로 Hot 스트림에 합류시키기 위한 registry
     */
    private final Sinks.Many<Flux<TikTokEvent>> streamRegistry =
            Sinks.many().multicast().onBackpressureBuffer();

    /**
     * 모든 스트리머 이벤트를 병합한 공유 Hot 스트림
     */
    private final Flux<TikTokEvent> hotStream = streamRegistry.asFlux()
            .flatMap(flux -> flux)
            .publish()
            .autoConnect(0);

    @PostConstruct
    void init() {
        hotStream.subscribe(
                event -> { },
                err -> log.error("hotStream 에러", err)
        );
    }

    public reactor.core.publisher.Mono<Void> register(String uniqueId) {
        if (sinksMap.containsKey(uniqueId)) {
            return reactor.core.publisher.Mono.error(
                    new IllegalArgumentException("이미 등록된 스트리머: " + uniqueId)
            );
        }

        // 내부 집계 정확성을 위해 여기서는 buffer 유지
        Sinks.Many<TikTokEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        sinksMap.put(uniqueId, sink);
        snapshots.put(uniqueId, StreamSnapshot.empty(uniqueId));

        Disposable subscription = connector.connect(uniqueId)
                .doOnNext(this::updateSnapshot)
                .subscribe(
                        event -> sink.tryEmitNext(event),
                        err -> log.error("[{}] 이벤트 스트림 에러", uniqueId, err),
                        () -> log.info("[{}] 이벤트 스트림 종료", uniqueId)
                );

        connections.put(uniqueId, subscription);
        streamRegistry.tryEmitNext(sink.asFlux());

        log.info("[{}] 스트리머 등록 완료", uniqueId);
        return reactor.core.publisher.Mono.empty();
    }

    public reactor.core.publisher.Mono<Void> unregister(String uniqueId) {
        Disposable conn = connections.remove(uniqueId);
        if (conn != null) {
            conn.dispose();
        }

        Sinks.Many<TikTokEvent> sink = sinksMap.remove(uniqueId);
        if (sink != null) {
            sink.tryEmitComplete();
        }

        snapshots.remove(uniqueId);
        log.info("[{}] 스트리머 해제 완료", uniqueId);
        return reactor.core.publisher.Mono.empty();
    }

    /**
     * 초기 진입용: 현재 snapshot 전체
     * 구독 시점마다 새로 생성되는 Cold
     */
    public Flux<TikTokEvent> coldSnapshots() {
        return Flux.defer(() ->
                Flux.fromIterable(new ArrayList<>(snapshots.values()))
                        .map(TikTokEvent::ofSnapshot)
        );
    }

    /**
     * 시청자 수 / 방 정보용 스트림
     * 최신 값만 중요하므로 latest + sample 적용
     */
    public Flux<TikTokEvent> liveStatusStream() {
        return hotStream
                .filter(this::isLiveStatusEvent)
                .sample(Duration.ofMillis(250))
                .onBackpressureLatest();
    }

    /**
     * 좋아요 / 댓글 / 선물 같은 빠른 이벤트용 스트림
     * UI 버벅임 방지를 위해 drop
     */
    public Flux<TikTokEvent> activityStream() {
        return hotStream
                .filter(this::isActivityEvent)
                .onBackpressureDrop(dropped ->
                        log.debug("dropped activity event: type={}, streamer={}",
                                dropped.getType(), dropped.getUniqueId()));
    }

    /**
     * 최종 관리자용 통합 스트림
     * 1) cold snapshot
     * 2) live status (latest)
     * 3) activity (drop)
     */
    public Flux<TikTokEvent> joinManager(String typeFilter) {
        Flux<TikTokEvent> cold = coldSnapshots();

        Flux<TikTokEvent> hotCombined = Flux.merge(
                liveStatusStream(),
                activityStream()
        );

        Flux<TikTokEvent> combined = Flux.concat(cold, hotCombined);

        if (typeFilter != null && !typeFilter.isBlank()) {
            return combined.filter(e -> typeFilter.equalsIgnoreCase(e.getType()));
        }
        return combined;
    }

    public Flux<StreamSnapshot> getSnapshots() {
        return Flux.fromIterable(snapshots.values());
    }

    public boolean injectEvent(TikTokEvent event) {
        Sinks.Many<TikTokEvent> sink = sinksMap.get(event.getUniqueId());
        if (sink == null) {
            return false;
        }
        updateSnapshot(event);
        sink.tryEmitNext(event);
        return true;
    }

    private boolean isLiveStatusEvent(TikTokEvent event) {
        String type = event.getType();
        return "member".equalsIgnoreCase(type)
                || "roomInfo".equalsIgnoreCase(type)
                || "snapshot".equalsIgnoreCase(type);
    }

    private boolean isActivityEvent(TikTokEvent event) {
        String type = event.getType();
        return "like".equalsIgnoreCase(type)
                || "chat".equalsIgnoreCase(type)
                || "gift".equalsIgnoreCase(type);
    }

    private void updateSnapshot(TikTokEvent event) {
        snapshots.computeIfPresent(event.getUniqueId(), (id, snap) -> {
            switch (event.getType()) {
                case "roomInfo" -> {
                    snap.setLive(true);
                    snap.setViewerCount(event.getViewerCount());
                    if (event.getFollowerCount() > 0) {
                        snap.setFollowerCount(event.getFollowerCount());
                    }
                    if (event.getTitle() != null) {
                        snap.setTitle(event.getTitle());
                    }
                }
                case "member" -> {
                    if (event.getViewerCount() > 0) {
                        snap.setViewerCount(event.getViewerCount());
                    }
                    snap.setLive(true);
                }
                case "gift" -> snap.setTotalGifts(snap.getTotalGifts() + event.getGiftCount());
                case "snapshot" -> snap.setLive(false);
            }
            return snap;
        });
    }
}