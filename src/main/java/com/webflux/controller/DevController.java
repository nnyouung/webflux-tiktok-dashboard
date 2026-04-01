package com.webflux.controller;

import com.webflux.domain.TikTokEvent;
import com.webflux.service.StreamManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 개발용 목 이벤트 주입 엔드포인트.
 * GET /dev/mock?uniqueId={id} 호출 시 해당 스트리머에 랜덤 이벤트를 Sinks에 주입한다.
 */
@RestController
@RequestMapping("/dev")
@RequiredArgsConstructor
public class DevController {

    private static final String[] NICKNAMES = {
            "tiktok_fan", "live_lover", "stream_king", "viewer007", "happy_user"
    };
    private static final String[] COMMENTS = {
            "Great stream!", "Hello everyone!", "Love this content!", "Amazing!", "Keep it up!",
            "So good!", "First time here", "This is fun", "Wow!", "Nice!"
    };
    private static final String[] STREAM_TITLES = {
            "Live with me!", "Daily stream", "Fun time", "Chill session", "Special broadcast"
    };

    private final StreamManagerService service;

    /**
     * 지정된 스트리머에 랜덤 이벤트 1건을 주입한다.
     * 스트리머가 등록되지 않은 경우 404 반환.
     */
    @GetMapping("/mock")
    public Mono<ResponseEntity<TikTokEvent>> mock(@RequestParam String uniqueId) {
        TikTokEvent event = randomEvent(uniqueId);
        boolean injected = service.injectEvent(event);
        if (injected) {
            return Mono.just(ResponseEntity.ok(event));
        }
        return Mono.just(ResponseEntity.notFound().build());
    }

    private TikTokEvent randomEvent(String uniqueId) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String nickname = NICKNAMES[rng.nextInt(NICKNAMES.length)];
        String userId = "mock_" + rng.nextInt(1000, 9999);

        return switch (rng.nextInt(4)) {
            case 0 -> TikTokEvent.builder()
                    .type("like")
                    .uniqueId(uniqueId)
                    .userId(userId)
                    .nickname(nickname)
                    .likeCount(rng.nextInt(1, 51))
                    .mock(true)
                    .build();
            case 1 -> TikTokEvent.builder()
                    .type("chat")
                    .uniqueId(uniqueId)
                    .userId(userId)
                    .nickname(nickname)
                    .comment(COMMENTS[rng.nextInt(COMMENTS.length)])
                    .mock(true)
                    .build();
            case 2 -> TikTokEvent.builder()
                    .type("member")
                    .uniqueId(uniqueId)
                    .userId(userId)
                    .nickname(nickname)
                    .viewerCount(rng.nextInt(100, 10_001))
                    .mock(true)
                    .build();
            default -> TikTokEvent.builder()
                    .type("roomInfo")
                    .uniqueId(uniqueId)
                    .viewerCount(rng.nextInt(100, 10_001))
                    .followerCount(rng.nextLong(1_000, 100_001))
                    .title(STREAM_TITLES[rng.nextInt(STREAM_TITLES.length)])
                    .mock(true)
                    .build();
        };
    }
}
