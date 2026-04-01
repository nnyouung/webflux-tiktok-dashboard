package com.webflux.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class TikTokEvent {

    String type;
    String uniqueId;    // 어느 스트리머의 이벤트인지
    String userId;
    String nickname;
    String comment;     // chat
    String giftName;    // gift
    int giftCount;      // gift
    int likeCount;      // like
    int viewerCount;    // member, roomInfo
    long followerCount; // roomInfo
    String title;       // roomInfo
    @Builder.Default
    boolean live = false; // snapshot 이벤트의 라이브 여부
    @Builder.Default
    boolean mock = false; // DevController에서 주입된 가짜 이벤트 여부
    @Builder.Default
    Instant timestamp = Instant.now();

    public static TikTokEvent ofSnapshot(StreamSnapshot snapshot) {
        return TikTokEvent.builder()
                .type("snapshot")
                .uniqueId(snapshot.getUniqueId())
                .viewerCount(snapshot.getViewerCount())
                .followerCount(snapshot.getFollowerCount())
                .title(snapshot.getTitle())
                .live(snapshot.isLive())
                .timestamp(snapshot.getCreatedAt())
                .build();
    }
}
