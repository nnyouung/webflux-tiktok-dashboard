package com.webflux.domain;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class StreamSnapshot {

    private String uniqueId;
    private boolean live;
    private int viewerCount;
    private int totalGifts;
    private long followerCount;
    private String title;
    @Builder.Default
    private Instant createdAt = Instant.now();

    public static StreamSnapshot empty(String uniqueId) {
        return StreamSnapshot.builder()
                .uniqueId(uniqueId)
                .live(false)
                .createdAt(Instant.now())
                .build();
    }
}
