package com.webflux.controller;

import com.webflux.domain.StreamSnapshot;
import com.webflux.domain.TikTokEvent;
import com.webflux.service.StreamManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/manager")
@RequiredArgsConstructor
public class ManagerController {

    private final StreamManagerService service;

    /**
     * SSE: Cold 스냅샷 전체 → Hot 실시간 이벤트.
     * type 쿼리 파라미터로 필터링 가능 (chat / gift / like / member).
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<TikTokEvent> stream(@RequestParam(required = false) String type) {
        return service.joinManager(type);
    }

    /**
     * 스트리머 등록 및 Euler WS 연결 시작.
     * 이미 등록된 uniqueId면 400, 성공 시 200.
     */
    @PostMapping("/register/{uniqueId}")
    public Mono<ResponseEntity<Void>> register(@PathVariable String uniqueId) {
        return service.register(uniqueId)
                .<ResponseEntity<Void>>thenReturn(ResponseEntity.<Void>ok().build())
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.<Void>badRequest().build()));
    }

    /**
     * 스트리머 연결 해제.
     */
    @DeleteMapping("/register/{uniqueId}")
    public Mono<ResponseEntity<Void>> unregister(@PathVariable String uniqueId) {
        return service.unregister(uniqueId)
                .thenReturn(ResponseEntity.<Void>noContent().<Void>build());
    }

    /**
     * 현재 등록된 스트리머 스냅샷 전체 조회 (REST, SSE 아님).
     */
    @GetMapping("/snapshot")
    public Flux<StreamSnapshot> snapshots() {
        return service.getSnapshots();
    }
}
