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

package com.nageoffer.ai.ragent.rag.core.retrieve.scope;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RetrievalScopeResolver {

    private final SearchChannelProperties properties;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public RetrievalScope resolve(List<SubQuestionIntent> intents) {
        if (!properties.getChannels().getIntentDirected().isEnabled()) {
            return new RetrievalScope(RetrievalScope.Type.GLOBAL, resolveAllCollections());
        }
        List<NodeScore> kbScores = intents == null ? List.of() : intents.stream()
                .flatMap(intent -> intent.nodeScores().stream())
                .filter(score -> score != null)
                .toList();
        double maxScore = NodeScoreFilters.kb(kbScores).stream()
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0.0D);
        List<String> directedCollections = resolveDirectedCollections(kbScores);
        double threshold = properties.getChannels().getVectorGlobal().getConfidenceThreshold();

        if (maxScore >= threshold && !directedCollections.isEmpty()) {
            return new RetrievalScope(RetrievalScope.Type.INTENT_DIRECTED, directedCollections);
        }
        return new RetrievalScope(RetrievalScope.Type.GLOBAL, resolveAllCollections());
    }

    private List<String> resolveDirectedCollections(List<NodeScore> scores) {
        double minScore = properties.getChannels().getIntentDirected().getMinIntentScore();
        LinkedHashSet<String> collections = new LinkedHashSet<>();
        NodeScoreFilters.kb(scores, minScore).stream()
                .map(NodeScore::getNode)
                .forEach(node -> addCollections(collections, node));
        return List.copyOf(collections);
    }

    private void addCollections(LinkedHashSet<String> collections, IntentNode node) {
        boolean hasCollectionNames = node.getCollectionNames() != null
                && node.getCollectionNames().stream()
                .anyMatch(name -> name != null && !name.isBlank());
        if (hasCollectionNames) {
            node.getCollectionNames().stream()
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(collections::add);
        } else if (node.getCollectionName() != null && !node.getCollectionName().isBlank()) {
            collections.add(node.getCollectionName());
        }
    }

    private List<String> resolveAllCollections() {
        LinkedHashSet<String> collections = new LinkedHashSet<>();
        List<KnowledgeBaseDO> knowledgeBases = knowledgeBaseMapper.selectList(
                Wrappers.<KnowledgeBaseDO>emptyWrapper()
        );
        knowledgeBases.stream()
                .map(KnowledgeBaseDO::getCollectionName)
                .filter(name -> name != null && !name.isBlank())
                .forEach(collections::add);
        return List.copyOf(collections);
    }
}
