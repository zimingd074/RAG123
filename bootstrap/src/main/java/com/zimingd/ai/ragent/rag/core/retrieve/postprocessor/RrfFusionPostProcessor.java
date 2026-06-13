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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RrfFusionPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "RrfFusion";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getFusion().getRrf().isEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        int k = Math.max(1, properties.getFusion().getRrf().getK());
        Map<String, FusedChunk> fused = new HashMap<>();

        for (SearchChannelResult result : results) {
            double weight = channelWeight(result.getChannelType());
            Set<String> seenInChannel = new HashSet<>();
            List<RetrievedChunk> rankedChunks = result.getChunks();
            for (int index = 0; index < rankedChunks.size(); index++) {
                RetrievedChunk chunk = rankedChunks.get(index);
                String key = chunkKey(chunk);
                if (!seenInChannel.add(key)) {
                    continue;
                }
                double contribution = weight / (k + index + 1.0D);
                fused.compute(key, (ignored, existing) -> {
                    if (existing == null) {
                        return new FusedChunk(chunk, contribution);
                    }
                    existing.score += contribution;
                    return existing;
                });
            }
        }

        List<FusedChunk> ranked = new ArrayList<>(fused.values());
        ranked.sort(Comparator.comparingDouble(FusedChunk::score).reversed()
                .thenComparing(entry -> chunkKey(entry.chunk)));
        return ranked.stream()
                .map(entry -> RetrievedChunk.builder()
                        .id(entry.chunk.getId())
                        .text(entry.chunk.getText())
                        .score((float) entry.score)
                        .build())
                .toList();
    }

    private double channelWeight(SearchChannelType type) {
        if (type == SearchChannelType.KEYWORD_PG || type == SearchChannelType.KEYWORD_ES) {
            return properties.getFusion().getRrf().getKeywordWeight();
        }
        return properties.getFusion().getRrf().getVectorWeight();
    }

    private String chunkKey(RetrievedChunk chunk) {
        return chunk.getId() != null ? chunk.getId() : String.valueOf(chunk.getText());
    }

    private static final class FusedChunk {

        private final RetrievedChunk chunk;
        private double score;

        private FusedChunk(RetrievedChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }

        private double score() {
            return score;
        }
    }
}
