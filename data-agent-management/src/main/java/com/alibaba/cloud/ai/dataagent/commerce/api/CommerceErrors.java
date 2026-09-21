/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.commerce.api;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.*;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice(basePackages="com.alibaba.cloud.ai.dataagent.commerce")
@Profile("commerce")
public class CommerceErrors {
    @ExceptionHandler(CommerceException.class)
    public ResponseEntity<ObjectNode> domain(CommerceException ex,ServerWebExchange exchange) {
        return ResponseEntity.status(ex.status()).body(object("code",ex.code(),"message",ex.getMessage(),"requestId",exchange.getAttribute("requestId"),"details",object()));
    }
    @ExceptionHandler({ServerWebInputException.class,IllegalArgumentException.class})
    public ResponseEntity<ObjectNode> input(Exception ex,ServerWebExchange exchange) {
        return domain(new CommerceException(422,"INVALID_REQUEST","请求参数无效，请检查输入"),exchange);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ObjectNode> unavailable(Exception ex,ServerWebExchange exchange) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Commerce request {} failed",(Object)exchange.getAttribute("requestId"),ex);
        return domain(new CommerceException(503,"DEPENDENCY_UNAVAILABLE","服务暂时不可用，请稍后重试"),exchange);
    }
}
