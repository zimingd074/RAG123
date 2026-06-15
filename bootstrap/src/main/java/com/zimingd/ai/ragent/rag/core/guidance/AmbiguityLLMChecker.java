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

package com.zimingd.ai.ragent.rag.core.guidance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zimingd.ai.ragent.framework.convention.ChatMessage;
import com.zimingd.ai.ragent.framework.convention.ChatRequest;
import com.zimingd.ai.ragent.infra.chat.LLMService;
import com.zimingd.ai.ragent.infra.util.LLMResponseCleaner;
import com.zimingd.ai.ragent.rag.core.intent.IntentNode;
import com.zimingd.ai.ragent.rag.core.intent.NodeScore;
import com.zimingd.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.zimingd.ai.ragent.rag.eval.EvalTraceSnapshotRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.zimingd.ai.ragent.rag.constant.RAGConstant.GUIDANCE_AMBIGUITY_CHECK_PROMPT_PATH;

/**
 * LLM 歧义确认器
 * 仅在规则层无法明确判断时调用，通过 LLM 语义理解确认是否存在品类歧义
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmbiguityLLMChecker {

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final EvalTraceSnapshotRecorder evalTraceRecorder;

    /**
     * 调用 LLM 确认是否存在歧义
     */
    public boolean checkAmbiguity(String question, List<NodeScore> ranked) {
        return checkAmbiguity(question, ranked, null);
    }

    public boolean checkAmbiguity(String question, List<NodeScore> ranked, String modelId) {
        String candidatesText = buildCandidatesText(ranked);
        String prompt = promptTemplateLoader.render(
                GUIDANCE_AMBIGUITY_CHECK_PROMPT_PATH,
                Map.of(
                        "question", question,
                        "candidates", candidatesText
                )
        );

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.user(prompt)
                ))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();

        long startedAt = System.currentTimeMillis();
        try {
            String raw = llmService.chat(request, modelId);
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            JsonElement root = JsonParser.parseString(cleaned);

            if (!root.isJsonObject()) {
                evalTraceRecorder.recordLlmStep(
                        "ambiguity-check",
                        modelId,
                        request,
                        raw,
                        false,
                        true,
                        "not_json_object",
                        System.currentTimeMillis() - startedAt
                );
                log.warn("歧义确认 LLM 返回非 JSON 对象: {}", raw);
                return true;
            }

            JsonObject obj = root.getAsJsonObject();
            if (obj.has("ambiguous")) {
                boolean ambiguous = obj.get("ambiguous").getAsBoolean();
                String reason = obj.has("reason") ? obj.get("reason").getAsString() : "";
                evalTraceRecorder.recordLlmStep(
                        "ambiguity-check",
                        modelId,
                        request,
                        raw,
                        true,
                        false,
                        null,
                        System.currentTimeMillis() - startedAt
                );
                log.info("LLM 歧义确认结果: ambiguous={}, reason={}, question={}", ambiguous, reason, question);
                return ambiguous;
            }

            evalTraceRecorder.recordLlmStep(
                    "ambiguity-check",
                    modelId,
                    request,
                    raw,
                    false,
                    true,
                    "missing_ambiguous",
                    System.currentTimeMillis() - startedAt
            );
            log.warn("歧义确认 LLM 返回缺少 ambiguous 字段: {}", raw);
            return true;
        } catch (Exception e) {
            evalTraceRecorder.recordLlmStep(
                    "ambiguity-check",
                    modelId,
                    request,
                    null,
                    false,
                    true,
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    System.currentTimeMillis() - startedAt
            );
            log.warn("歧义确认 LLM 调用失败, 降级为触发澄清, question={}", question, e);
            return true;
        }
    }

    private String buildCandidatesText(List<NodeScore> ranked) {
        return ranked.stream()
                .map(ns -> {
                    IntentNode node = ns.getNode();
                    String systemPath = node.getFullPath() != null ? node.getFullPath() : node.getName();
                    return String.format("- 品类ID: %s, 名称: %s, 路径: %s, 分数: %.2f",
                            node.getId(), node.getName(), systemPath, ns.getScore());
                })
                .collect(Collectors.joining("\n"));
    }
}
