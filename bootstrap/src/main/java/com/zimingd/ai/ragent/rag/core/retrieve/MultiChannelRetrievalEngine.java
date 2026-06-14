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

package com.zimingd.ai.ragent.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import com.zimingd.ai.ragent.framework.convention.RetrievedChunk;
import com.zimingd.ai.ragent.framework.trace.RagTraceNode;
import com.zimingd.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.zimingd.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.zimingd.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.zimingd.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.zimingd.ai.ragent.rag.core.retrieve.scope.RetrievalScopeResolver;
import com.zimingd.ai.ragent.rag.dto.SubQuestionIntent;
import com.zimingd.ai.ragent.rag.trace.RetrievalTraceRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> searchChannels;
    private final List<SearchResultPostProcessor> postProcessors;
    private final Executor ragRetrievalExecutor;
    private final RetrievalScopeResolver retrievalScopeResolver;
    private final RetrievalTraceRecorder traceRecorder;

    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents, int topK) {
        SearchContext context = buildSearchContext(subIntents, topK);
        List<SearchChannelResult> channelResults = executeSearchChannels(context);
        if (CollUtil.isEmpty(channelResults)) {
            return List.of();
        }
        return executePostProcessors(channelResults, context);
    }

    private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
        List<SearchChannel> enabledChannels = searchChannels.stream()
                .filter(channel -> channel.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchChannel::getPriority))
                .toList();
        if (enabledChannels.isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(channel -> CompletableFuture.supplyAsync(
                        () -> executeSearchChannel(channel, context),
                        ragRetrievalExecutor
                ))
                .toList();
        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();
    }

    private SearchChannelResult executeSearchChannel(SearchChannel channel, SearchContext context) {
        RetrievalTraceRecorder.Span span = traceRecorder.begin(
                channelTraceName(channel),
                "RETRIEVE_CHANNEL",
                channelInitialData(channel, context)
        );
        try {
            SearchChannelResult result = channel.search(context);
            Map<String, Object> data = channelResultData(result);
            if (result.isSuccess()) {
                span.success(data);
            } else {
                span.error(new IllegalStateException(result.getErrorMessage()), data);
            }
            return result;
        } catch (Exception e) {
            log.error("Search channel {} failed", channel.getName(), e);
            span.error(e, Map.of("channel", channel.getName(), "candidateCount", 0));
            return emptyResult(channel);
        }
    }

    private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                       SearchContext context) {
        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
                .filter(processor -> processor.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();
        if (enabledProcessors.isEmpty()) {
            return results.stream()
                    .flatMap(result -> result.getChunks().stream())
                    .collect(Collectors.toList());
        }

        List<RetrievedChunk> chunks = results.stream()
                .flatMap(result -> result.getChunks().stream())
                .collect(Collectors.toList());
        for (SearchResultPostProcessor processor : enabledProcessors) {
            int beforeSize = chunks.size();
            RetrievalTraceRecorder.Span span = traceRecorder.begin(
                    processorTraceName(processor),
                    "POST_PROCESSOR",
                    Map.of("processor", processor.getName(), "inputCandidates", beforeSize)
            );
            try {
                chunks = processor.process(chunks, results, context);
                span.success(processorTraceData(processor, context, beforeSize, chunks.size()));
            } catch (Exception e) {
                log.error("Post processor {} failed; preserving previous ranking", processor.getName(), e);
                span.error(e, Map.of(
                        "processor", processor.getName(),
                        "inputCandidates", beforeSize,
                        "outputCandidates", chunks.size()
                ));
            }
        }
        return chunks;
    }

    private SearchChannelResult emptyResult(SearchChannel channel) {
        return SearchChannelResult.builder()
                .channelType(channel.getType())
                .channelName(channel.getName())
                .chunks(List.of())
                .success(false)
                .errorMessage("Unhandled channel exception")
                .build();
    }

    private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents, int topK) {
        String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();
        RetrievalTraceRecorder.Span span = traceRecorder.begin(
                "retrieval-scope-resolve",
                "RETRIEVE_SCOPE",
                Map.of("intentCount", subIntents == null ? 0 : subIntents.size())
        );
        try {
            var scope = retrievalScopeResolver.resolve(subIntents);
            span.success(Map.of(
                    "scopeType", scope.type().name(),
                    "collectionCount", scope.collectionNames().size(),
                    "collections", scope.collectionNames()
            ));
            return SearchContext.builder()
                    .originalQuestion(question)
                    .rewrittenQuestion(question)
                    .intents(subIntents)
                    .topK(topK)
                    .retrievalScope(scope)
                    .build();
        } catch (RuntimeException e) {
            span.error(e, Map.of());
            throw e;
        }
    }

    private String channelTraceName(SearchChannel channel) {
        return switch (channel.getType()) {
            case INTENT_DIRECTED -> "vector-intent-search";
            case VECTOR_GLOBAL -> "vector-global-search";
            case KEYWORD_PG -> "keyword-pg-search";
            default -> channel.getName();
        };
    }

    private Map<String, Object> channelInitialData(SearchChannel channel, SearchContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel", channel.getName());
        data.put("topK", context.getTopK());
        if (context.getRetrievalScope() != null) {
            data.put("scopeType", context.getRetrievalScope().type().name());
            data.put("collectionCount", context.getRetrievalScope().collectionNames().size());
        }
        return data;
    }

    private Map<String, Object> channelResultData(SearchChannelResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel", result.getChannelName());
        data.put("candidateCount", result.getChunks().size());
        data.put("latencyMs", result.getLatencyMs());
        data.put("success", result.isSuccess());
        if (result.getErrorMessage() != null) {
            data.put("errorMessage", result.getErrorMessage());
        }
        data.putAll(result.getMetadata());
        data.put("chunkIds", result.getChunks().stream().map(RetrievedChunk::getId).toList());
        return data;
    }

    private String processorTraceName(SearchResultPostProcessor processor) {
        return switch (processor.getName()) {
            case "RrfFusion" -> "rrf-fusion";
            case "Deduplication" -> "chunk-deduplication";
            case "Rerank" -> "rerank";
            case "FinalTopK" -> "final-topk";
            default -> processor.getName();
        };
    }

    private Map<String, Object> processorTraceData(SearchResultPostProcessor processor,
                                                   SearchContext context,
                                                   int beforeSize,
                                                   int afterSize) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("processor", processor.getName());
        data.put("inputCandidates", beforeSize);
        data.put("outputCandidates", afterSize);
        String metadataKey = switch (processor.getName()) {
            case "RrfFusion" -> "rrf";
            case "Deduplication" -> "deduplication";
            case "Rerank" -> "rerank";
            case "FinalTopK" -> "finalTopK";
            default -> null;
        };
        if (metadataKey != null && context.getMetadata().get(metadataKey) instanceof Map<?, ?> details) {
            details.forEach((key, value) -> data.put(String.valueOf(key), value));
        }
        return data;
    }
}
