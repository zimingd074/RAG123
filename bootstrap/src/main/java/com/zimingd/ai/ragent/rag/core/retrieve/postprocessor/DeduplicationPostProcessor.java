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
import com.zimingd.ai.ragent.rag.config.SearchChannelProperties;
import com.zimingd.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.zimingd.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.zimingd.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "Deduplication";
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();
        results.stream()
                .sorted((left, right) -> Integer.compare(
                        channelPriority(left.getChannelType()),
                        channelPriority(right.getChannelType())
                ))
                .forEach(result -> result.getChunks().forEach(chunk -> merge(chunkMap, chunk)));

        // RRF already returns one row per chunk. Preserve its ranking and scores when enabled.
        if (properties.getFusion().getRrf().isEnabled()) {
            Map<String, RetrievedChunk> currentOrder = new LinkedHashMap<>();
            chunks.forEach(chunk -> currentOrder.putIfAbsent(chunkKey(chunk), chunk));
            List<RetrievedChunk> output = new ArrayList<>(currentOrder.values());
            context.getMetadata().put("deduplication", Map.of(
                    "inputCandidates", chunks.size(),
                    "outputCandidates", output.size(),
                    "duplicatesRemoved", chunks.size() - output.size()
            ));
            return output;
        }
        List<RetrievedChunk> output = new ArrayList<>(chunkMap.values());
        context.getMetadata().put("deduplication", Map.of(
                "inputCandidates", chunks.size(),
                "outputCandidates", output.size(),
                "duplicatesRemoved", chunks.size() - output.size()
        ));
        return output;
    }

    private void merge(Map<String, RetrievedChunk> chunks, RetrievedChunk candidate) {
        String key = chunkKey(candidate);
        RetrievedChunk existing = chunks.get(key);
        if (existing == null || score(candidate) > score(existing)) {
            chunks.put(key, candidate);
        }
    }

    private float score(RetrievedChunk chunk) {
        return chunk.getScore() == null ? Float.NEGATIVE_INFINITY : chunk.getScore();
    }

    private String chunkKey(RetrievedChunk chunk) {
        return chunk.getId() != null ? chunk.getId() : String.valueOf(chunk.getText());
    }

    private int channelPriority(SearchChannelType type) {
        return switch (type) {
            case INTENT_DIRECTED -> 1;
            case KEYWORD_ES, KEYWORD_PG -> 2;
            case VECTOR_GLOBAL -> 3;
            default -> 99;
        };
    }
}
