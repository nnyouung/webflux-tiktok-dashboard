package com.webflux.controller;

import com.webflux.domain.StreamSnapshot;
import com.webflux.domain.TikTokEvent;
import com.webflux.service.StreamManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
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
     * SSE: Cold snapshot 전체 → Hot 실시간 이벤트
     * type 쿼리 파라미터로 필터링 가능
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<TikTokEvent>> stream(@RequestParam(required = false) String type) {
        return service.joinManager(type)
                .onBackpressureLatest()
                .map(event -> ServerSentEvent.<TikTokEvent>builder()
                        .event(event.getType())
                        .data(event)
                        .build());
    }

    @PostMapping("/register/{uniqueId}")
    public Mono<ResponseEntity<Void>> register(@PathVariable String uniqueId) {
        return service.register(uniqueId)
                .<ResponseEntity<Void>>thenReturn(ResponseEntity.ok().build())
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.badRequest().build()));
    }

    @DeleteMapping("/register/{uniqueId}")
    public Mono<ResponseEntity<Void>> unregister(@PathVariable String uniqueId) {
        return service.unregister(uniqueId)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @GetMapping("/snapshot")
    public Flux<StreamSnapshot> snapshots() {
        return service.getSnapshots();
    }
}