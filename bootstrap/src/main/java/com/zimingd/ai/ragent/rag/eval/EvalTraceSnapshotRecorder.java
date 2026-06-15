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
import com.zimingd.ai.ragent.framework.convention.ChatMessage;
import com.zimingd.ai.ragent.framework.convention.ChatRequest;
import com.zimingd.ai.ragent.framework.convention.RetrievedChunk;
import com.zimingd.ai.ragent.infra.chat.StreamCallback;
import com.zimingd.ai.ragent.infra.config.AIModelProperties;
import com.zimingd.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.zimingd.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.zimingd.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.zimingd.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.zimingd.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.zimingd.ai.ragent.rag.core.intent.NodeScore;
import com.zimingd.ai.ragent.rag.core.rewrite.RewriteResult;
import com.zimingd.ai.ragent.rag.dto.RetrievalContext;
import com.zimingd.ai.ragent.rag.dto.SubQuestionIntent;
import com.zimingd.ai.ragent.rag.trace.RetrievalTraceRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Records evaluation-only snapshots on the same trace as the production chat request.
 */
@Component
@RequiredArgsConstructor
public class EvalTraceSnapshotRecorder {

    private final EvalProperties evalProperties;
    private final RetrievalTraceRecorder traceRecorder;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final AIModelProperties aiModelProperties;

    public void recordModels(String routingModelId, String answerModelId) {
        AIModelProperties.ModelCandidate routingModel = findModel(routingModelId);
        AIModelProperties.ModelCandidate answerModel = findModel(answerModelId);
        record("eval-model-selection", "EVAL_MODEL", mapOf(
                "routingModelId", defaultModel(routingModelId),
                "routingProvider", routingModel == null ? null : routingModel.getProvider(),
                "routingProviderModel", routingModel == null ? null : routingModel.getModel(),
                "answerModelId", defaultModel(answerModelId),
                "answerProvider", answerModel == null ? null : answerModel.getProvider(),
                "answerProviderModel", answerModel == null ? null : answerModel.getModel(),
                "thinking", false
        ));
    }

    public void recordRewrite(String originalQuestion,
                              RewriteResult result,
                              String routingModelId) {
        record("eval-query-rewrite", "EVAL_REWRITE", mapOf(
                "routingModelId", defaultModel(routingModelId),
                "originalQuestion", originalQuestion,
                "rewrittenQuestion", result == null ? null : result.rewrittenQuestion(),
                "subQuestions", result == null ? List.of() : result.subQuestions(),
                "fallback", result == null || CollUtil.isEmpty(result.subQuestions())
        ));
    }

    public void recordIntents(List<SubQuestionIntent> subIntents, String routingModelId) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (subIntents != null) {
            for (SubQuestionIntent subIntent : subIntents) {
                List<Map<String, Object>> candidates = new ArrayList<>();
                if (subIntent.nodeScores() != null) {
                    for (NodeScore score : subIntent.nodeScores()) {
                        candidates.add(mapOf(
                                "intentId", score.getNode() == null ? null : score.getNode().getId(),
                                "intentName", score.getNode() == null ? null : score.getNode().getName(),
                                "score", score.getScore()
                        ));
                    }
                }
                items.add(mapOf(
                        "subQuestion", subIntent.subQuestion(),
                        "candidates", candidates
                ));
            }
        }
        record("eval-intent-result", "EVAL_INTENT", mapOf(
                "routingModelId", defaultModel(routingModelId),
                "subIntents", items
        ));
    }

    public void recordGuidance(GuidanceDecision decision, String routingModelId) {
        record("eval-ambiguity-result", "EVAL_GUIDANCE", mapOf(
                "routingModelId", defaultModel(routingModelId),
                "triggered", decision != null && decision.isPrompt(),
                "action", decision == null ? null : decision.getAction().name(),
                "prompt", decision == null ? null : decision.getPrompt(),
                "reason", decision == null ? null : decision.getReason()
        ));
    }

    public void recordLlmStep(String step,
                              String modelId,
                              ChatRequest request,
                              String rawOutput,
                              boolean parsed,
                              boolean fallback,
                              String error,
                              long latencyMs) {
        AIModelProperties.ModelCandidate model = findModel(modelId);
        record("eval-" + step + "-llm", "EVAL_LLM", mapOf(
                "step", step,
                "modelId", defaultModel(modelId),
                "provider", model == null ? null : model.getProvider(),
                "providerModel", model == null ? null : model.getModel(),
                "rawOutput", rawOutput,
                "parsed", parsed,
                "fallback", fallback,
                "error", error,
                "latencyMs", latencyMs,
                "estimatedInputTokens", estimateMessages(request == null ? null : request.getMessages()),
                "estimatedOutputTokens", estimateTokens(rawOutput),
                "usageEstimated", true
        ));
    }

    public void recordRetrieval(RetrievalContext context) {
        List<RetrievedChunk> chunks = flattenChunks(context);
        List<String> chunkIds = chunks.stream().map(RetrievedChunk::getId).toList();
        List<String> contexts = chunks.stream().map(RetrievedChunk::getText).toList();
        List<String> contextDocIds = resolveContextDocIds(chunks);
        record("eval-retrieval-result", "EVAL_RETRIEVAL", mapOf(
                "retrievedChunkIds", chunkIds,
                "retrievedContexts", contexts,
                "retrievedContextDocIds", contextDocIds,
                "retrievedDocIds", dedupNonBlank(contextDocIds),
                "hasKb", context != null && context.hasKb(),
                "hasMcp", context != null && context.hasMcp(),
                "contextHash", sha256(String.join("\n", contexts))
        ));
    }

    public StreamCallback traceGeneration(List<ChatMessage> messages,
                                          RetrievalContext context,
                                          String answerModelId,
                                          StreamCallback delegate) {
        if (!evalProperties.isEnabled()) {
            return delegate;
        }
        long started = System.currentTimeMillis();
        AtomicLong firstTokenMs = new AtomicLong(-1L);
        StringBuilder answer = new StringBuilder();
        RetrievalTraceRecorder.Span span = traceRecorder.begin(
                "eval-answer-generation",
                "EVAL_GENERATE",
                mapOf(
                        "answerModelId", defaultModel(answerModelId),
                        "messageCount", messages == null ? 0 : messages.size()
                )
        );
        AtomicBoolean finished = new AtomicBoolean(false);
        return new StreamCallback() {
            @Override
            public void onContent(String content) {
                if (StrUtil.isNotEmpty(content)) {
                    firstTokenMs.compareAndSet(-1L, System.currentTimeMillis() - started);
                    answer.append(content);
                }
                delegate.onContent(content);
            }

            @Override
            public void onThinking(String content) {
                delegate.onThinking(content);
            }

            @Override
            public void onComplete() {
                finish(true, null);
                delegate.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                finish(false, error);
                delegate.onError(error);
            }

            private void finish(boolean success, Throwable error) {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                Map<String, Object> data = generationData(
                        messages,
                        context,
                        answerModelId,
                        answer.toString(),
                        firstTokenMs.get()
                );
                if (success) {
                    span.success(data);
                } else {
                    span.error(error, data);
                }
            }
        };
    }

    private Map<String, Object> generationData(List<ChatMessage> messages,
                                               RetrievalContext context,
                                               String answerModelId,
                                               String answer,
                                               long firstTokenMs) {
        AIModelProperties.ModelCandidate model = findModel(answerModelId);
        List<Map<String, String>> serializedMessages = messages == null
                ? List.of()
                : messages.stream()
                .map(message -> Map.of(
                        "role", message.getRole().name(),
                        "content", StrUtil.emptyIfNull(message.getContent())
                ))
                .toList();
        String input = serializedMessages.stream()
                .map(message -> message.get("role") + "\n" + message.get("content"))
                .collect(Collectors.joining("\n"));
        return mapOf(
                "answerModelId", defaultModel(answerModelId),
                "provider", model == null ? null : model.getProvider(),
                "providerModel", model == null ? null : model.getModel(),
                "messages", serializedMessages,
                "inputHash", sha256(input),
                "contextHash", sha256(context == null ? "" : StrUtil.emptyIfNull(context.getKbContext())),
                "firstTokenMs", firstTokenMs < 0 ? null : firstTokenMs,
                "answer", answer,
                "estimatedInputTokens", estimateTokens(input),
                "estimatedOutputTokens", estimateTokens(answer),
                "usageEstimated", true
        );
    }

    private void record(String name, String type, Map<String, Object> data) {
        if (!evalProperties.isEnabled()) {
            return;
        }
        RetrievalTraceRecorder.Span span = traceRecorder.begin(name, type, Map.of());
        span.success(data);
    }

    private List<RetrievedChunk> flattenChunks(RetrievalContext context) {
        if (context == null || CollUtil.isEmpty(context.getIntentChunks())) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        return context.getIntentChunks().values().stream()
                .filter(CollUtil::isNotEmpty)
                .flatMap(List::stream)
                .filter(chunk -> chunk != null && StrUtil.isNotBlank(chunk.getId()))
                .filter(chunk -> seen.add(chunk.getId()))
                .toList();
    }

    private List<String> resolveContextDocIds(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return List.of();
        }
        List<String> chunkIds = chunks.stream()
                .map(RetrievedChunk::getId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        Map<String, String> chunkToDocument = knowledgeChunkMapper.selectByIds(chunkIds).stream()
                .filter(chunk -> StrUtil.isNotBlank(chunk.getId()) && StrUtil.isNotBlank(chunk.getDocId()))
                .collect(Collectors.toMap(
                        KnowledgeChunkDO::getId,
                        KnowledgeChunkDO::getDocId,
                        (left, right) -> left
                ));
        List<String> documentIds = chunkToDocument.values().stream().distinct().toList();
        Map<String, String> documentToBusinessId = documentIds.isEmpty()
                ? Map.of()
                : knowledgeDocumentMapper.selectByIds(documentIds).stream()
                .filter(document -> StrUtil.isNotBlank(document.getId())
                        && StrUtil.isNotBlank(document.getDocName()))
                .collect(Collectors.toMap(
                        KnowledgeDocumentDO::getId,
                        document -> stripExtension(document.getDocName()),
                        (left, right) -> left
                ));
        return chunks.stream()
                .map(chunk -> documentToBusinessId.get(chunkToDocument.get(chunk.getId())))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<String> dedupNonBlank(List<String> values) {
        if (CollUtil.isEmpty(values)) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<>();
        return values.stream()
                .filter(StrUtil::isNotBlank)
                .filter(seen::add)
                .toList();
    }

    private String stripExtension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String defaultModel(String modelId) {
        return StrUtil.blankToDefault(modelId, "default");
    }

    private AIModelProperties.ModelCandidate findModel(String modelId) {
        if (StrUtil.isBlank(modelId) || aiModelProperties.getChat() == null) {
            return null;
        }
        return aiModelProperties.getChat().getCandidates().stream()
                .filter(candidate -> modelId.equals(candidate.getId()))
                .findFirst()
                .orElse(null);
    }

    private int estimateTokens(String text) {
        return Math.max(0, (StrUtil.length(text) + 1) / 2);
    }

    private int estimateMessages(List<ChatMessage> messages) {
        if (CollUtil.isEmpty(messages)) {
            return 0;
        }
        String input = messages.stream()
                .map(message -> message.getRole().name() + "\n" + StrUtil.emptyIfNull(message.getContent()))
                .collect(Collectors.joining("\n"));
        return estimateTokens(input);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(StrUtil.emptyIfNull(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }
}
