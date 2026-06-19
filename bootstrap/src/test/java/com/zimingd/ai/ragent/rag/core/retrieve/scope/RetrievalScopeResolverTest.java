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

package com.zimingd.ai.ragent.rag.core.retrieve.scope;

import com.zimingd.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.zimingd.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.zimingd.ai.ragent.rag.config.SearchChannelProperties;
import com.zimingd.ai.ragent.rag.core.intent.IntentNode;
import com.zimingd.ai.ragent.rag.core.intent.NodeScore;
import com.zimingd.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetrievalScopeResolverTest {

    @Test
    void usesIntentCollectionsAtConfidenceBoundary() {
        SearchChannelProperties properties = new SearchChannelProperties();
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        RetrievalScopeResolver resolver = new RetrievalScopeResolver(properties, mapper);
        IntentNode node = IntentNode.builder()
                .collectionNames(List.of("kb-manual", "kb-faq"))
                .build();

        RetrievalScope scope = resolver.resolve(List.of(
                new SubQuestionIntent("question", List.of(new NodeScore(node, 0.6D)))
        ));

        assertEquals(RetrievalScope.Type.INTENT_DIRECTED, scope.type());
        assertEquals(List.of("kb-manual", "kb-faq"), scope.collectionNames());
        verifyNoInteractions(mapper);
    }

    @Test
    void fallsBackToAllCollectionsBelowThreshold() {
        SearchChannelProperties properties = new SearchChannelProperties();
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().collectionName("kb-a").build(),
                KnowledgeBaseDO.builder().collectionName("kb-b").build(),
                KnowledgeBaseDO.builder().collectionName("kb-a").build()
        ));
        RetrievalScopeResolver resolver = new RetrievalScopeResolver(properties, mapper);
        IntentNode node = IntentNode.builder().collectionName("kb-directed").build();

        RetrievalScope scope = resolver.resolve(List.of(
                new SubQuestionIntent("question", List.of(new NodeScore(node, 0.59D)))
        ));

        assertEquals(RetrievalScope.Type.GLOBAL, scope.type());
        assertEquals(List.of("kb-a", "kb-b"), scope.collectionNames());
    }

    @Test
    void fallsBackToGlobalWhenConfidentIntentHasNoCollection() {
        SearchChannelProperties properties = new SearchChannelProperties();
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().collectionName("kb-global").build()
        ));
        RetrievalScopeResolver resolver = new RetrievalScopeResolver(properties, mapper);

        RetrievalScope scope = resolver.resolve(List.of(
                new SubQuestionIntent("question", List.of(
                        new NodeScore(IntentNode.builder().build(), 0.9D)
                ))
        ));

        assertEquals(RetrievalScope.Type.GLOBAL, scope.type());
        assertEquals(List.of("kb-global"), scope.collectionNames());
    }
}
