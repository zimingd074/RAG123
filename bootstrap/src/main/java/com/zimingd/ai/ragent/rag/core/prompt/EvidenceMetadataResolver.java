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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.zimingd.ai.ragent.framework.convention.RetrievedChunk;
import com.zimingd.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.zimingd.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.zimingd.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.zimingd.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceMetadataResolver {

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    public List<EvidenceMetadata> resolve(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return List.of();
        }
        try {
            return resolveInternal(chunks);
        } catch (Exception e) {
            log.warn("Resolve evidence metadata failed, fallback to unknown metadata: {}", e.toString());
            return unknownMetadata(chunks);
        }
    }

    private List<EvidenceMetadata> resolveInternal(List<RetrievedChunk> chunks) {
        List<String> chunkIds = chunks.stream()
                .map(RetrievedChunk::getId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        if (chunkIds.isEmpty()) {
            return unknownMetadata(chunks);
        }

        Map<String, KnowledgeChunkDO> chunkMap = knowledgeChunkMapper.selectByIds(chunkIds).stream()
                .filter(chunk -> StrUtil.isNotBlank(chunk.getId()))
                .collect(Collectors.toMap(
                        KnowledgeChunkDO::getId,
                        Function.identity(),
                        (left, right) -> left));

        List<String> docIds = chunkMap.values().stream()
                .map(KnowledgeChunkDO::getDocId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        Map<String, KnowledgeDocumentDO> documentMap = docIds.isEmpty()
                ? Collections.emptyMap()
                : knowledgeDocumentMapper.selectByIds(docIds).stream()
                .filter(document -> StrUtil.isNotBlank(document.getId()))
                .collect(Collectors.toMap(
                        KnowledgeDocumentDO::getId,
                        Function.identity(),
                        (left, right) -> left));

        return IntStream.range(0, chunks.size())
                .mapToObj(index -> metadataFor(index + 1, chunks.get(index), chunkMap, documentMap))
                .toList();
    }

    private EvidenceMetadata metadataFor(int evidenceNumber,
                                         RetrievedChunk chunk,
                                         Map<String, KnowledgeChunkDO> chunkMap,
                                         Map<String, KnowledgeDocumentDO> documentMap) {
        String chunkId = chunk == null ? null : chunk.getId();
        if (StrUtil.isBlank(chunkId)) {
            return EvidenceMetadata.unknown(evidenceNumber, chunkId);
        }

        KnowledgeChunkDO chunkDO = chunkMap.get(chunkId);
        if (chunkDO == null || StrUtil.isBlank(chunkDO.getDocId())) {
            return EvidenceMetadata.unknown(evidenceNumber, chunkId);
        }

        KnowledgeDocumentDO documentDO = documentMap.get(chunkDO.getDocId());
        if (documentDO == null || StrUtil.isBlank(documentDO.getDocName())) {
            return new EvidenceMetadata(
                    "E" + evidenceNumber,
                    EvidenceMetadata.UNKNOWN,
                    EvidenceMetadata.UNKNOWN,
                    EvidenceMetadata.blankToUnknown(chunkId),
                    chunkDO.getChunkIndex()
            );
        }

        String docName = documentDO.getDocName();
        return new EvidenceMetadata(
                "E" + evidenceNumber,
                stripExtension(docName),
                docName,
                EvidenceMetadata.blankToUnknown(chunkId),
                chunkDO.getChunkIndex()
        );
    }

    private List<EvidenceMetadata> unknownMetadata(List<RetrievedChunk> chunks) {
        return IntStream.range(0, chunks.size())
                .mapToObj(index -> EvidenceMetadata.unknown(index + 1, chunks.get(index) == null ? null : chunks.get(index).getId()))
                .toList();
    }

    private String stripExtension(String fileName) {
        if (StrUtil.isBlank(fileName)) {
            return EvidenceMetadata.UNKNOWN;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
