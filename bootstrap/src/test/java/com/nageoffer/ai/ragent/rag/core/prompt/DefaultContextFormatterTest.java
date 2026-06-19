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

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultContextFormatterTest {

    @Test
    void formatsSingleIntentWithEvidenceMetadata() {
        EvidenceMetadataResolver resolver = mock(EvidenceMetadataResolver.class);
        when(resolver.resolve(anyList())).thenReturn(List.of(
                new EvidenceMetadata("E1", "PROD_VAC_001", "PROD_VAC_001.md", "chunk-1", 0),
                new EvidenceMetadata("E2", "FAQ_VAC_001", "FAQ_VAC_001.md", "chunk-2", 3)
        ));
        DefaultContextFormatter formatter = formatter(resolver);
        IntentNode node = IntentNode.builder().id("S6").promptSnippet("只回答兼容结论").build();

        String context = formatter.formatKbContext(
                List.of(new NodeScore(node, 1.0D)),
                Map.of("S6", List.of(chunk("chunk-1", "T7 滤芯说明"), chunk("chunk-2", "G10S Pro 兼容说明"))),
                5
        );

        assertTrue(context.contains("<rules>"));
        assertTrue(context.contains("[E1 | doc_id=PROD_VAC_001 | doc_name=PROD_VAC_001.md | chunk_id=chunk-1 | chunk_index=0]"));
        assertTrue(context.contains("[E2 | doc_id=FAQ_VAC_001 | doc_name=FAQ_VAC_001.md | chunk_id=chunk-2 | chunk_index=3]"));
        assertTrue(context.contains("T7 滤芯说明"));
        assertTrue(context.contains("G10S Pro 兼容说明"));
    }

    @Test
    void deduplicatesMultiIntentChunksAndKeepsStableOrder() {
        EvidenceMetadataResolver resolver = mock(EvidenceMetadataResolver.class);
        when(resolver.resolve(anyList())).thenAnswer(invocation -> {
            List<RetrievedChunk> chunks = invocation.getArgument(0);
            return IntStream.range(0, chunks.size())
                    .mapToObj(index -> new EvidenceMetadata(
                            "E" + (index + 1),
                            "DOC_" + (index + 1),
                            "DOC_" + (index + 1) + ".md",
                            chunks.get(index).getId(),
                            index))
                    .toList();
        });
        DefaultContextFormatter formatter = formatter(resolver);
        IntentNode s12 = IntentNode.builder().id("S12").promptSnippet("联动规则").build();
        IntentNode s9 = IntentNode.builder().id("S9").promptSnippet("网络规则").build();

        String context = formatter.formatKbContext(
                List.of(new NodeScore(s12, 1.0D), new NodeScore(s9, 0.9D)),
                Map.of(
                        "S12", List.of(chunk("chunk-1", "第一条"), chunk("chunk-2", "第二条")),
                        "S9", List.of(chunk("chunk-1", "第一条重复"), chunk("chunk-3", "第三条"))
                ),
                10
        );

        assertTrue(context.indexOf("[E1 | doc_id=DOC_1") < context.indexOf("[E2 | doc_id=DOC_2"));
        assertTrue(context.indexOf("[E2 | doc_id=DOC_2") < context.indexOf("[E3 | doc_id=DOC_3"));
        assertTrue(context.contains("第一条"));
        assertFalse(context.contains("第一条重复"));
        assertEquals(3, count(context, "[E"));
    }

    @Test
    void formatsUnknownMetadataAndHonorsTopK() {
        EvidenceMetadataResolver resolver = mock(EvidenceMetadataResolver.class);
        when(resolver.resolve(anyList())).thenReturn(List.of(
                EvidenceMetadata.unknown(1, null),
                EvidenceMetadata.unknown(2, "chunk-2")
        ));
        DefaultContextFormatter formatter = formatter(resolver);

        String context = formatter.formatKbContext(
                List.of(),
                Map.of("multi-channel", List.of(
                        chunk(null, "无 ID 证据"),
                        chunk("chunk-2", "第二条证据"),
                        chunk("chunk-3", "不应进入")
                )),
                2
        );

        assertTrue(context.contains("[E1 | doc_id=unknown | doc_name=unknown | chunk_id=unknown | chunk_index=unknown]"));
        assertTrue(context.contains("[E2 | doc_id=unknown | doc_name=unknown | chunk_id=chunk-2 | chunk_index=unknown]"));
        assertTrue(context.contains("无 ID 证据"));
        assertTrue(context.contains("第二条证据"));
        assertFalse(context.contains("不应进入"));
    }

    private DefaultContextFormatter formatter(EvidenceMetadataResolver resolver) {
        return new DefaultContextFormatter(new PromptTemplateLoader(new DefaultResourceLoader()), resolver);
    }

    private RetrievedChunk chunk(String id, String text) {
        return RetrievedChunk.builder()
                .id(id)
                .text(text)
                .score(1F)
                .build();
    }

    private int count(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
