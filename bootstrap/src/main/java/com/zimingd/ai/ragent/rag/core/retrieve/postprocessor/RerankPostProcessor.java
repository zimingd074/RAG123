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

package com.zimingd.ai.ragent.rag.core.retrieve.postprocessor;

import com.zimingd.ai.ragent.framework.convention.RetrievedChunk;
import com.zimingd.ai.ragent.infra.rerank.RerankService;
import com.zimingd.ai.ragent.infra.config.AIModelProperties;
import com.zimingd.ai.ragent.rag.config.RAGConfigProperties;
import com.zimingd.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.zimingd.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.InterruptedIOException;

/**
 * Rerank 后置处理器
 * <p>
 * 使用 Rerank 模型对结果进行重排序
 * 这是最后一个处理器，输出最终的 Top-K 结果
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    private final RerankService rerankService;
    private final RAGConfigProperties ragConfigProperties;
    private final AIModelProperties aiModelProperties;

    @Override
    public String getName() {
        return "Rerank";
    }

    @Override
    public int getOrder() {
        return 10;  // 最后执行
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return ragConfigProperties.getRerankEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            log.info("Chunk 列表为空，跳过 Rerank");
            return chunks;
        }

        int topK = context.getTopK();
        int candidateLimit = Math.min(
                Math.max(1, ragConfigProperties.getRerankCandidateLimit()),
                Math.max(1, topK * 3)
        );
        List<RetrievedChunk> candidates = chunks.stream().limit(candidateLimit).toList();
        long totalTextChars = candidates.stream()
                .map(RetrievedChunk::getText)
                .filter(text -> text != null)
                .mapToLong(text -> Math.min(text.length(), ragConfigProperties.getRerankDocumentMaxChars()))
                .sum();
        long start = System.currentTimeMillis();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", aiModelProperties.getRerank().getDefaultModel());
        metadata.put("inputCandidates", candidates.size());
        metadata.put("candidateLimit", candidateLimit);
        metadata.put("documentMaxChars", ragConfigProperties.getRerankDocumentMaxChars());
        metadata.put("requestTextChars", totalTextChars);
        metadata.put("timeoutMs", ragConfigProperties.getRerankTimeoutMs());

        try {
            List<RetrievedChunk> reranked = rerankService.rerank(
                    context.getMainQuestion(),
                    candidates,
                    topK
            );
            metadata.put("latencyMs", System.currentTimeMillis() - start);
            metadata.put("fallbackToRrf", false);
            metadata.put("rankingChanges", rankingChanges(candidates, reranked));
            context.getMetadata().put("rerank", metadata);
            return reranked;
        } catch (Exception e) {
            if (!Boolean.TRUE.equals(ragConfigProperties.getRerankFallbackToRrf())) {
                throw e instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new IllegalStateException(e);
            }
            metadata.put("latencyMs", System.currentTimeMillis() - start);
            metadata.put("fallbackToRrf", true);
            metadata.put("timedOut", isTimeout(e));
            metadata.put("errorType", e.getClass().getSimpleName());
            metadata.put("errorMessage", e.getMessage());
            context.getMetadata().put("rerank", metadata);
            log.warn("Rerank failed, fallback to RRF ranking", e);
            return candidates.stream().limit(topK).toList();
        }
    }

    private List<Map<String, Object>> rankingChanges(List<RetrievedChunk> before,
                                                      List<RetrievedChunk> after) {
        Map<String, Integer> beforeRanks = new LinkedHashMap<>();
        for (int index = 0; index < before.size(); index++) {
            beforeRanks.put(before.get(index).getId(), index + 1);
        }
        java.util.ArrayList<Map<String, Object>> changes = new java.util.ArrayList<>();
        for (int index = 0; index < after.size(); index++) {
            RetrievedChunk chunk = after.get(index);
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("chunkId", chunk.getId());
            change.put("rrfRank", beforeRanks.get(chunk.getId()));
            change.put("rerankRank", index + 1);
            change.put("rerankScore", chunk.getScore());
            changes.add(change);
        }
        return changes;
    }

    private boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedIOException
                    || current.getClass().getSimpleName().toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
