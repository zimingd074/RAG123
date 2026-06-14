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

package com.zimingd.ai.ragent.rag.core.retrieve.channel.strategy;

import com.zimingd.ai.ragent.framework.convention.RetrievedChunk;
import com.zimingd.ai.ragent.rag.core.intent.IntentNode;
import com.zimingd.ai.ragent.rag.core.intent.NodeScore;
import com.zimingd.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.zimingd.ai.ragent.rag.core.retrieve.RetrieverService;
import com.zimingd.ai.ragent.rag.core.retrieve.channel.AbstractParallelRetriever;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 意图并行检索器
 * 继承模板类，实现意图特定的检索逻辑
 */
@Slf4j
public class IntentParallelRetriever extends AbstractParallelRetriever<IntentParallelRetriever.IntentTask> {

    private final RetrieverService retrieverService;

    public record IntentTask(NodeScore nodeScore, String collectionName, int intentTopK) {
    }

    public IntentParallelRetriever(RetrieverService retrieverService,
                                   Executor executor) {
        super(executor);
        this.retrieverService = retrieverService;
    }

    /**
     * 执行并行检索（重载方法，支持动态 TopK 计算）
     */
    public List<RetrievedChunk> executeParallelRetrieval(float[] queryVector,
                                                         List<NodeScore> targets,
                                                         int fallbackTopK,
                                                         int topKMultiplier) {
        List<IntentTask> intentTasks = targets.stream()
                .flatMap(nodeScore -> resolveCollectionNames(nodeScore.getNode()).stream()
                        .map(collectionName -> new IntentTask(
                                nodeScore,
                                collectionName,
                                resolveIntentTopK(nodeScore, fallbackTopK, topKMultiplier)
                        )))
                .toList();
        return super.executeParallelRetrieval(queryVector, intentTasks, fallbackTopK);
    }

    @Override
    protected List<RetrievedChunk> createRetrievalTask(float[] queryVector, IntentTask task, int ignoredTopK) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        try {
            return retrieverService.retrieveByVector(
                    queryVector,
                    RetrieveRequest.builder()
                            .collectionName(task.collectionName())
                            .topK(task.intentTopK())
                            .build()
            );
        } catch (Exception e) {
            log.error("意图检索失败 - 意图ID: {}, 意图名称: {}, Collection: {}, 错误: {}",
                    node.getId(), node.getName(), task.collectionName(), e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    protected String getTargetIdentifier(IntentTask task) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        return String.format(
                "意图ID: %s, 意图名称: %s, Collection: %s",
                node.getId(), node.getName(), task.collectionName());
    }

    private List<String> resolveCollectionNames(IntentNode node) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (node.getCollectionNames() != null) {
            node.getCollectionNames().stream()
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(names::add);
        }
        if (names.isEmpty() && node.getCollectionName() != null
                && !node.getCollectionName().isBlank()) {
            names.add(node.getCollectionName());
        }
        return List.copyOf(names);
    }

    @Override
    protected String getStatisticsName() {
        return "意图检索";
    }

    /**
     * 计算单个意图节点检索 TopK
     */
    private int resolveIntentTopK(NodeScore nodeScore, int fallbackTopK, int topKMultiplier) {
        int baseTopK = fallbackTopK;
        if (nodeScore != null && nodeScore.getNode() != null) {
            Integer nodeTopK = nodeScore.getNode().getTopK();
            if (nodeTopK != null && nodeTopK > 0) {
                baseTopK = nodeTopK;
            }
        }

        if (topKMultiplier <= 0) {
            log.warn("意图定向通道倍率配置异常: {}, 使用基础 TopK: {}", topKMultiplier, baseTopK);
            return baseTopK;
        }

        return baseTopK * topKMultiplier;
    }
}
