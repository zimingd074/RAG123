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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionPostProcessorTest {

    @Test
    void accumulatesRanksAcrossChannels() {
        RrfFusionPostProcessor processor = new RrfFusionPostProcessor(new SearchChannelProperties());
        SearchChannelResult vector = result(
                SearchChannelType.VECTOR_GLOBAL,
                chunk("shared"),
                chunk("vector-only")
        );
        SearchChannelResult keyword = result(
                SearchChannelType.KEYWORD_PG,
                chunk("keyword-only"),
                chunk("shared")
        );

        List<RetrievedChunk> fused = processor.process(
                List.of(),
                List.of(vector, keyword),
                SearchContext.builder().topK(10).build()
        );

        assertEquals("shared", fused.get(0).getId());
        assertTrue(fused.get(0).getScore() > fused.get(1).getScore());
        assertEquals(3, fused.size());
    }

    @Test
    void handlesSingleChannel() {
        RrfFusionPostProcessor processor = new RrfFusionPostProcessor(new SearchChannelProperties());
        SearchChannelResult vector = result(
                SearchChannelType.INTENT_DIRECTED,
                chunk("first"),
                chunk("second")
        );

        List<RetrievedChunk> fused = processor.process(
                List.of(),
                List.of(vector),
                SearchContext.builder().topK(10).build()
        );

        assertEquals(List.of("first", "second"), fused.stream().map(RetrievedChunk::getId).toList());
    }

    private SearchChannelResult result(SearchChannelType type, RetrievedChunk... chunks) {
        return SearchChannelResult.builder()
                .channelType(type)
                .channelName(type.name())
                .chunks(List.of(chunks))
                .build();
    }

    private RetrievedChunk chunk(String id) {
        return RetrievedChunk.builder().id(id).text(id).score(1.0F).build();
    }
}
