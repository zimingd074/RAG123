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

package com.zimingd.ai.ragent.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.zimingd.ai.ragent.framework.convention.RetrievedChunk;
import com.zimingd.ai.ragent.rag.config.SearchChannelProperties;
import com.zimingd.ai.ragent.rag.core.retrieve.RetrieverService;
import com.zimingd.ai.ragent.rag.core.retrieve.channel.strategy.CollectionParallelRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class VectorGlobalSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final RetrieverService retrieverService;
    private final CollectionParallelRetriever parallelRetriever;

    public VectorGlobalSearchChannel(RetrieverService retrieverService,
                                     SearchChannelProperties properties,
                                     Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.retrieverService = retrieverService;
        this.parallelRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
    }

    @Override
    public String getName() {
        return "VectorGlobalSearch";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getVectorGlobal().isEnabled()
                && context.getRetrievalScope() != null
                && context.getRetrievalScope().isGlobal()
                && CollUtil.isNotEmpty(context.getRetrievalScope().collectionNames());
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            List<String> collections = context.getRetrievalScope().collectionNames();
            int multiplier = properties.getChannels().getVectorGlobal().getTopKMultiplier();
            long embeddingStart = System.currentTimeMillis();
            float[] queryVector = retrieverService.embedQuery(context.getMainQuestion());
            long embeddingLatency = System.currentTimeMillis() - embeddingStart;
            long vectorSearchStart = System.currentTimeMillis();
            List<RetrievedChunk> chunks = parallelRetriever.executeParallelRetrieval(
                    queryVector,
                    collections,
                    context.getTopK() * multiplier
            );
            long vectorSearchLatency = System.currentTimeMillis() - vectorSearchStart;
            long latency = System.currentTimeMillis() - startTime;
            log.info("Global vector retrieval completed, chunks={}, latencyMs={}", chunks.size(), latency);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("collectionCount", collections.size());
            metadata.put("embeddingLatencyMs", embeddingLatency);
            metadata.put("vectorSearchLatencyMs", vectorSearchLatency);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            log.error("Global vector retrieval failed", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR_GLOBAL;
    }
}
