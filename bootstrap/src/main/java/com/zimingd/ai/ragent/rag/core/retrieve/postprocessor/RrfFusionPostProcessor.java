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
import java.util.LinkedHashMap;

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
        Map<String, Map<String, Object>> provenance = new HashMap<>();
        int inputCandidates = 0;

        for (SearchChannelResult result : results) {
            Set<String> seenInChannel = new HashSet<>();
            List<RetrievedChunk> rankedChunks = result.getChunks();
            inputCandidates += rankedChunks.size();
            for (int index = 0; index < rankedChunks.size(); index++) {
                RetrievedChunk chunk = rankedChunks.get(index);
                String key = chunkKey(chunk);
                if (!seenInChannel.add(key)) {
                    continue;
                }
                double weight = channelWeight(result, chunk);
                double contribution = weight / (k + index + 1.0D);
                Map<String, Object> ranks = provenance.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
                ranks.put(channelRankKey(result.getChannelType()), index + 1);
                ranks.put("sources", sourceNames(ranks.get("sources"), result.getChannelName()));
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
        List<RetrievedChunk> output = new ArrayList<>();
        List<Map<String, Object>> ranking = new ArrayList<>();
        for (int index = 0; index < ranked.size(); index++) {
            FusedChunk entry = ranked.get(index);
            String key = chunkKey(entry.chunk);
            output.add(RetrievedChunk.builder()
                    .id(entry.chunk.getId())
                    .text(entry.chunk.getText())
                    .score((float) entry.score)
                    .build());
            Map<String, Object> detail = new LinkedHashMap<>(provenance.getOrDefault(key, Map.of()));
            detail.put("chunkId", entry.chunk.getId());
            detail.put("rrfRank", index + 1);
            detail.put("rrfScore", entry.score);
            ranking.add(detail);
        }
        context.getMetadata().put("rrf", Map.of(
                "rrfK", k,
                "inputCandidates", inputCandidates,
                "outputCandidates", output.size(),
                "crossChannelDuplicates", Math.max(0, inputCandidates - output.size()),
                "ranking", ranking
        ));
        return output;
    }

    private double channelWeight(SearchChannelResult result, RetrievedChunk chunk) {
        SearchChannelType type = result.getChannelType();
        if (type == SearchChannelType.KEYWORD_PG || type == SearchChannelType.KEYWORD_ES) {
            Object exactIds = result.getMetadata().get("exactIdentifierChunkIds");
            if (exactIds instanceof List<?> ids && ids.contains(chunk.getId())) {
                return properties.getFusion().getRrf().getExactIdentifierWeight();
            }
            return properties.getFusion().getRrf().getKeywordWeight();
        }
        return properties.getFusion().getRrf().getVectorWeight();
    }

    private String channelRankKey(SearchChannelType type) {
        return switch (type) {
            case KEYWORD_ES, KEYWORD_PG -> "keywordRank";
            default -> "vectorRank";
        };
    }

    private List<String> sourceNames(Object existing, String source) {
        List<String> values = new ArrayList<>();
        if (existing instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(values::add);
        }
        if (!values.contains(source)) {
            values.add(source);
        }
        return values;
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
