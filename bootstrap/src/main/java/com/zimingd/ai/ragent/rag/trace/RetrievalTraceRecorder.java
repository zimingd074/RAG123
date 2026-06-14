/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zimingd.ai.ragent.rag.trace;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zimingd.ai.ragent.framework.trace.RagTraceContext;
import com.zimingd.ai.ragent.rag.config.RagTraceProperties;
import com.zimingd.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.zimingd.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalTraceRecorder {

    private static final int MAX_NODE_TYPE_LENGTH = 16;
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";

    private final RagTraceProperties traceProperties;
    private final RagTraceRecordService traceRecordService;
    private final ObjectMapper objectMapper;

    public Span begin(String name, String type, Map<String, ?> initialData) {
        String traceId = RagTraceContext.getTraceId();
        if (!traceProperties.isEnabled() || StrUtil.isBlank(traceId)) {
            return Span.noop();
        }

        String nodeId = IdUtil.getSnowflakeNextIdStr();
        try {
            traceRecordService.startNode(RagTraceNodeDO.builder()
                    .traceId(traceId)
                    .nodeId(nodeId)
                    .parentNodeId(RagTraceContext.currentNodeId())
                    .depth(RagTraceContext.depth())
                    .nodeType(normalizeNodeType(type))
                    .nodeName(name)
                    .status(STATUS_RUNNING)
                    .startTime(new Date())
                    .extraData(toJson(initialData))
                    .build());
            return new Span(traceId, nodeId, System.currentTimeMillis(), this);
        } catch (RuntimeException e) {
            log.warn("Start retrieval trace node failed, continuing retrieval: nodeName={}", name, e);
            return Span.noop();
        }
    }

    private void finish(Span span, String status, Throwable error, Map<String, ?> data) {
        try {
            traceRecordService.finishNode(
                    span.traceId,
                    span.nodeId,
                    status,
                    truncateError(error),
                    new Date(),
                    System.currentTimeMillis() - span.startMillis,
                    toJson(data)
            );
        } catch (RuntimeException e) {
            log.warn("Finish retrieval trace node failed, continuing retrieval: nodeId={}", span.nodeId, e);
        }
    }

    private String normalizeNodeType(String type) {
        String normalized = StrUtil.blankToDefault(type, "RETRIEVE_STAGE");
        if (normalized.length() <= MAX_NODE_TYPE_LENGTH) {
            return normalized;
        }
        log.warn("Retrieval trace node type is too long and will be truncated: {}", normalized);
        return normalized.substring(0, MAX_NODE_TYPE_LENGTH);
    }

    private String toJson(Map<String, ?> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Serialize retrieval trace extra_data failed", e);
            return null;
        }
    }

    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": "
                + StrUtil.blankToDefault(throwable.getMessage(), "");
        int max = traceProperties.getMaxErrorLength();
        return message.length() <= max ? message : message.substring(0, max);
    }

    public static final class Span {

        private static final Span NOOP = new Span(null, null, 0L, null);

        private final String traceId;
        private final String nodeId;
        private final long startMillis;
        private final RetrievalTraceRecorder recorder;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private Span(String traceId,
                     String nodeId,
                     long startMillis,
                     RetrievalTraceRecorder recorder) {
            this.traceId = traceId;
            this.nodeId = nodeId;
            this.startMillis = startMillis;
            this.recorder = recorder;
        }

        public static Span noop() {
            return NOOP;
        }

        public void success(Map<String, ?> data) {
            finish(STATUS_SUCCESS, null, data);
        }

        public void error(Throwable error, Map<String, ?> data) {
            finish(STATUS_ERROR, error, data);
        }

        private void finish(String status, Throwable error, Map<String, ?> data) {
            if (recorder == null || !finished.compareAndSet(false, true)) {
                return;
            }
            recorder.finish(this, status, error, data);
        }
    }
}
