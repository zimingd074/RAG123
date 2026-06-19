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

package com.zimingd.ai.ragent.rag.core.prompt;

import com.zimingd.ai.ragent.framework.convention.RetrievedChunk;
import com.zimingd.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.zimingd.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.zimingd.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.zimingd.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidenceMetadataResolverTest {

    @Test
    void resolvesBusinessDocIdFromDocumentName() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        EvidenceMetadataResolver resolver = new EvidenceMetadataResolver(chunkMapper, documentMapper);

        when(chunkMapper.selectByIds(List.of("chunk-1"))).thenReturn(List.of(
                KnowledgeChunkDO.builder()
                        .id("chunk-1")
                        .docId("doc-internal-1")
                        .chunkIndex(2)
                        .build()
        ));
        when(documentMapper.selectByIds(List.of("doc-internal-1"))).thenReturn(List.of(
                KnowledgeDocumentDO.builder()
                        .id("doc-internal-1")
                        .docName("PROD_VAC_001.md")
                        .build()
        ));

        List<EvidenceMetadata> metadata = resolver.resolve(List.of(chunk("chunk-1", "text")));

        assertEquals(1, metadata.size());
        assertEquals("E1", metadata.get(0).evidenceId());
        assertEquals("PROD_VAC_001", metadata.get(0).docId());
        assertEquals("PROD_VAC_001.md", metadata.get(0).docName());
        assertEquals("chunk-1", metadata.get(0).chunkId());
        assertEquals(2, metadata.get(0).chunkIndex());
    }

    @Test
    void fallsBackToUnknownWhenMetadataIsMissing() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        EvidenceMetadataResolver resolver = new EvidenceMetadataResolver(chunkMapper, documentMapper);

        when(chunkMapper.selectByIds(List.of("missing-chunk"))).thenReturn(List.of());

        List<EvidenceMetadata> metadata = resolver.resolve(List.of(
                chunk(null, "no id"),
                chunk("missing-chunk", "missing")
        ));

        assertEquals(2, metadata.size());
        assertEquals("unknown", metadata.get(0).docId());
        assertEquals("unknown", metadata.get(0).chunkId());
        assertEquals("unknown", metadata.get(1).docId());
        assertEquals("missing-chunk", metadata.get(1).chunkId());
    }

    @Test
    void mapperFailureDoesNotThrow() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        EvidenceMetadataResolver resolver = new EvidenceMetadataResolver(chunkMapper, documentMapper);

        when(chunkMapper.selectByIds(List.of("chunk-1"))).thenThrow(new RuntimeException("db down"));

        List<EvidenceMetadata> metadata = resolver.resolve(List.of(chunk("chunk-1", "text")));

        assertEquals(1, metadata.size());
        assertEquals("E1", metadata.get(0).evidenceId());
        assertEquals("unknown", metadata.get(0).docId());
        assertEquals("chunk-1", metadata.get(0).chunkId());
    }

    private RetrievedChunk chunk(String id, String text) {
        return RetrievedChunk.builder()
                .id(id)
                .text(text)
                .score(1F)
                .build();
    }
}
