package com.webflux.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * Euler Stream WebSocket API 개별 메시지 DTO.
 * 실제 Euler 응답 구조: { "type": "...", "data": { ... } }
 * data 내부 구조는 type마다 다르므로 JsonNode로 수신 후 EulerConnector에서 파싱.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
class EulerMessage {
    private String type;
    private JsonNode data; // 타입별로 중첩 구조가 다름
}
