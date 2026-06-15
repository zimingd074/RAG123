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

package com.zimingd.ai.ragent.rag.eval;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.zimingd.ai.ragent.framework.convention.ChatRequest;
import com.zimingd.ai.ragent.infra.chat.StreamCallback;
import com.zimingd.ai.ragent.infra.chat.StreamCancellationHandle;
import com.zimingd.ai.ragent.infra.chat.LLMService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * Replays a frozen final prompt against one answer model without rerunning retrieval.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.eval", name = "enabled", havingValue = "true")
public class EvalGenerationController {

    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final LLMService llmService;

    @PostMapping(
            value = "/rag/eval/generate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter generate(@RequestBody EvalGenerationRequest request) {
        if (request == null || CollUtil.isEmpty(request.getMessages())) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (StrUtil.isBlank(request.getAnswerModelId())) {
            throw new IllegalArgumentException("answerModelId must not be blank");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(request.getMessages())
                .temperature(request.getTemperature())
                .topP(request.getTopP())
                .thinking(false)
                .build();

        StreamCancellationHandle handle = llmService.streamChat(
                chatRequest,
                new StreamCallback() {
                    @Override
                    public void onContent(String content) {
                        send(emitter, "message", Map.of("type", "response", "delta", content));
                    }

                    @Override
                    public void onComplete() {
                        send(emitter, "finish", Map.of(
                                "answerModelId", request.getAnswerModelId()
                        ));
                        send(emitter, "done", "[DONE]");
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        emitter.completeWithError(error);
                    }
                },
                request.getAnswerModelId()
        );
        emitter.onTimeout(handle::cancel);
        emitter.onCompletion(handle::cancel);
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
