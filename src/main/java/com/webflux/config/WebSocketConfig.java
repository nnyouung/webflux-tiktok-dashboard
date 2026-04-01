package com.webflux.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;

@Configuration
public class WebSocketConfig {

    @Bean
    public WebSocketClient webSocketClient() {
        // ReactorNettyWebSocketClient는 내부에서 WebsocketClientSpec을 별도로 생성한다.
        // 따라서 HttpClient.websocket(...)만 설정하면 maxFramePayloadLength가 적용되지 않을 수 있어,
        // Spring이 실제로 사용하는 spec builder supplier를 주입한다.
        return new ReactorNettyWebSocketClient(
                HttpClient.create(),
                () -> WebsocketClientSpec.builder()
                        .maxFramePayloadLength(10 * 1024 * 1024) // 10MB
        );
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
