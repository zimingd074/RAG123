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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentParallelRetrieverTest {

    @Test
    void retrievesEveryConfiguredCollectionAndUsesNodeTopK() {
        RetrieverService retrieverService = mock(RetrieverService.class);
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenReturn(List.of());
        IntentParallelRetriever retriever = new IntentParallelRetriever(
                retrieverService,
                Runnable::run
        );
        IntentNode node = IntentNode.builder()
                .id("F1_故障报告")
                .name("故障报告")
                .collectionName("legacy-faq")
                .collectionNames(List.of("kb-faq", "kb-manual", "kb-faq"))
                .topK(4)
                .build();

        float[] queryVector = {1.0F, 0.0F};
        retriever.executeParallelRetrieval(
                queryVector,
                List.of(new NodeScore(node, 0.9D)),
                5,
                2
        );

        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(
                RetrieveRequest.class
        );
        verify(retrieverService, times(2))
                .retrieveByVector(same(queryVector), captor.capture());
        assertEquals(
                List.of("kb-faq", "kb-manual"),
                captor.getAllValues().stream()
                        .map(RetrieveRequest::getCollectionName)
                        .toList()
        );
        captor.getAllValues().forEach(request -> assertEquals(8, request.getTopK()));
    }

    @Test
    void fallsBackToLegacyCollectionName() {
        RetrieverService retrieverService = mock(RetrieverService.class);
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenReturn(List.of());
        IntentParallelRetriever retriever = new IntentParallelRetriever(
                retrieverService,
                Runnable::run
        );
        IntentNode node = IntentNode.builder()
                .id("S2_参数咨询")
                .name("参数咨询")
                .collectionName("kb-product")
                .build();

        float[] queryVector = {1.0F, 0.0F};
        retriever.executeParallelRetrieval(
                queryVector,
                List.of(new NodeScore(node, 0.9D)),
                5,
                1
        );

        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(
                RetrieveRequest.class
        );
        verify(retrieverService).retrieveByVector(same(queryVector), captor.capture());
        assertEquals("kb-product", captor.getValue().getCollectionName());
        assertEquals(5, captor.getValue().getTopK());
    }

    @Test
    void globallySortsScoresAcrossCollections() {
        RetrieverService retrieverService = mock(RetrieverService.class);
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenAnswer(invocation -> {
                    RetrieveRequest request = invocation.getArgument(1);
                    float score = "kb-first".equals(request.getCollectionName()) ? 0.4F : 0.9F;
                    return List.of(RetrievedChunk.builder()
                            .id(request.getCollectionName())
                            .score(score)
                            .build());
                });
        IntentParallelRetriever retriever = new IntentParallelRetriever(
                retrieverService,
                Runnable::run
        );
        IntentNode node = IntentNode.builder()
                .id("test")
                .name("test")
                .collectionNames(List.of("kb-first", "kb-second"))
                .build();

        List<RetrievedChunk> result = retriever.executeParallelRetrieval(
                new float[]{1.0F, 0.0F},
                List.of(new NodeScore(node, 0.9D)),
                5,
                1
        );

        assertEquals(List.of("kb-second", "kb-first"),
                result.stream().map(RetrievedChunk::getId).toList());
    }
}
