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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 스트리머 등록/해제 및 Cold+Hot 이벤트 스트림을 관리한다.
 *
 * <p>Hot 스트림은 streamRegistry(Sinks of Fluxes)를 통해 동적으로 합쳐지므로,
 * SSE 클라이언트가 연결된 후 새로 등록된 스트리머의 이벤트도 자동으로 수신된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamManagerService {

    private final EulerConnector connector;

    // 스트리머별 이벤트 Sinks (Euler 이벤트 → SSE 구독자로 브로드캐스트)
    private final ConcurrentHashMap<String, Sinks.Many<TikTokEvent>> sinksMap = new ConcurrentHashMap<>();

    // 스트리머별 현재 상태 스냅샷
    private final ConcurrentHashMap<String, StreamSnapshot> snapshots = new ConcurrentHashMap<>();

    // 스트리머별 Euler WS 구독 핸들
    private final ConcurrentHashMap<String, Disposable> connections = new ConcurrentHashMap<>();

    /**
     * 새 스트리머 Flux를 동적으로 Hot 스트림에 합류시키기 위한 "publisher of publishers".
     * register() 호출 시 해당 스트리머의 Flux를 emit한다.
     */
    private final Sinks.Many<Flux<TikTokEvent>> streamRegistry =
            Sinks.many().multicast().onBackpressureBuffer();

    /**
     * 모든 스트리머 이벤트를 병합한 공유 Hot 스트림.
     * autoConnect(0): 구독자 없이도 즉시 활성화 → 늦게 SSE 연결한 클라이언트도 놓치지 않음.
     */
    private final Flux<TikTokEvent> hotStream = streamRegistry.asFlux()
            .flatMap(flux -> flux)
            .publish()
            .autoConnect(0);

    @PostConstruct
    void init() {
        // publish().autoConnect(0) 으로 이미 연결되지만,
        // 명시적으로 hotStream 을 구동하여 streamRegistry 구독이 시작되도록 한다.
        hotStream.subscribe(
                event -> { /* 개별 sink 구독자에게 이미 전달됨 */ },
                err -> log.error("hotStream 에러", err)
        );
    }

    /**
     * 스트리머를 등록하고 Euler WS 연결을 시작한다.
     *
     * @throws IllegalArgumentException uniqueId가 이미 등록된 경우
     */
    public Mono<Void> register(String uniqueId) {
        if (sinksMap.containsKey(uniqueId)) {
            return Mono.error(new IllegalArgumentException(
                    "이미 등록된 스트리머: " + uniqueId));
        }

        // multicast: 여러 SSE 클라이언트가 동일 스트리머 이벤트를 받을 수 있음
        Sinks.Many<TikTokEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        sinksMap.put(uniqueId, sink);
        snapshots.put(uniqueId, StreamSnapshot.empty(uniqueId));

        // Euler WS 연결 → snapshot 갱신 → sink 에 emit
        Disposable subscription = connector.connect(uniqueId)
                .doOnNext(event -> updateSnapshot(event))
                .subscribe(
                        event -> sink.tryEmitNext(event),
                        err -> log.error("[{}] 이벤트 스트림 에러", uniqueId, err),
                        () -> log.info("[{}] 이벤트 스트림 종료", uniqueId)
                );

        connections.put(uniqueId, subscription);

        // streamRegistry 에 이 스트리머의 Flux 를 추가 → hotStream 에 동적 합류
        streamRegistry.tryEmitNext(sink.asFlux());

        log.info("[{}] 스트리머 등록 완료", uniqueId);
        return Mono.empty();
    }

    /**
     * 스트리머 연결을 해제하고 관련 리소스를 제거한다.
     */
    public Mono<Void> unregister(String uniqueId) {
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
        return Mono.empty();
    }

    /**
     * SSE 구독용 스트림을 반환한다.
     * <ul>
     *   <li>Cold: 구독 시점의 스냅샷 전체</li>
     *   <li>Hot: 이후 발생하는 모든 스트리머의 실시간 이벤트</li>
     * </ul>
     *
     * @param typeFilter null 이면 전체, 값이 있으면 해당 type 만 필터
     */
    public Flux<TikTokEvent> joinManager(String typeFilter) {
        // Flux.defer: 구독 시점의 snapshots 상태 캡처 (이후 변경 미반영)
        Flux<TikTokEvent> cold = Flux.defer(() ->
                Flux.fromIterable(new ArrayList<>(snapshots.values()))
                        .map(TikTokEvent::ofSnapshot)
        );

        Flux<TikTokEvent> combined = Flux.concat(cold, hotStream);

        if (typeFilter != null && !typeFilter.isBlank()) {
            return combined.filter(e -> typeFilter.equalsIgnoreCase(e.getType()));
        }
        return combined;
    }

    /**
     * 현재 등록된 스트리머 스냅샷 전체를 반환한다 (REST 전용, SSE 아님).
     */
    public Flux<StreamSnapshot> getSnapshots() {
        return Flux.fromIterable(snapshots.values());
    }

    /**
     * 외부에서 이벤트를 직접 Sink에 주입한다 (개발용 목 데이터 주입).
     *
     * @return 주입 성공 여부 (등록된 스트리머가 없으면 false)
     */
    public boolean injectEvent(TikTokEvent event) {
        Sinks.Many<TikTokEvent> sink = sinksMap.get(event.getUniqueId());
        if (sink == null) {
            return false;
        }
        updateSnapshot(event);
        sink.tryEmitNext(event);
        return true;
    }

    // ── 스냅샷 갱신 ────────────────────────────────────────────────────────────

    private void updateSnapshot(TikTokEvent event) {
        snapshots.computeIfPresent(event.getUniqueId(), (id, snap) -> {
            switch (event.getType()) {
                case "roomInfo" -> {
                    snap.setLive(true);
                    snap.setViewerCount(event.getViewerCount());
                    if (event.getFollowerCount() > 0) snap.setFollowerCount(event.getFollowerCount());
                    if (event.getTitle() != null) snap.setTitle(event.getTitle());
                }
                case "member" -> snap.setViewerCount(event.getViewerCount());
                case "gift"   -> snap.setTotalGifts(snap.getTotalGifts() + event.getGiftCount());
                case "snapshot" -> snap.setLive(false); // 4404 not-live 이벤트
            }
            return snap;
        });
    }
}
