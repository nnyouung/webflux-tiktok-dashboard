package com.webflux.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
class EulerResponse {
    private List<EulerMessage> messages;
}
