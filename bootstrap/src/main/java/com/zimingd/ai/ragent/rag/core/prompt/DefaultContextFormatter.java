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
import com.zimingd.ai.ragent.rag.core.intent.IntentNode;
import com.zimingd.ai.ragent.rag.core.intent.NodeScore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.zimingd.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

@Service
@RequiredArgsConstructor
public class DefaultContextFormatter implements ContextFormatter {

    private final PromptTemplateLoader templateLoader;
    private final EvidenceMetadataResolver evidenceMetadataResolver;

    @Override
    public String formatKbContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        if (rerankedByIntent == null || rerankedByIntent.isEmpty()) {
            return "";
        }
        if (CollUtil.isEmpty(kbIntents)) {
            return formatChunksWithoutIntent(rerankedByIntent, topK);
        }
        if (kbIntents.size() > 1) {
            return formatMultiIntentContext(kbIntents, rerankedByIntent, topK);
        }
        return formatSingleIntentContext(kbIntents.get(0), rerankedByIntent, topK);
    }

    /**
     * 格式化单意图上下文
     */
    private String formatSingleIntentContext(NodeScore nodeScore, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        IntentNode node = nodeScore == null ? null : nodeScore.getNode();
        String nodeId = node == null ? null : node.getId();
        List<RetrievedChunk> chunks = rerankedByIntent.get(nodeId);
        if (CollUtil.isEmpty(chunks)) {
            return "";
        }
        String snippet = StrUtil.emptyIfNull(node == null ? null : node.getPromptSnippet()).trim();
        String body = formatEvidenceItems(chunks, topK);
        return renderKbSection(renderSnippetRules(snippet), body);
    }

    /**
     * 格式化多意图上下文
     */
    private String formatMultiIntentContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        // 1. 合并所有意图的回答规则
        List<String> snippets = kbIntents.stream()
                .map(NodeScore::getNode)
                .filter(node -> node != null)
                .map(IntentNode::getPromptSnippet)
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();

        String snippetSection = "";
        if (!snippets.isEmpty()) {
            String numberedRules = IntStream.range(0, snippets.size())
                    .mapToObj(i -> (i + 1) + ". " + snippets.get(i))
                    .collect(Collectors.joining("\n"));
            snippetSection = renderSnippetRules(numberedRules);
        }

        // 2. 合并所有意图的文档片段（去重）
        List<RetrievedChunk> allChunks = flattenByIntentOrder(kbIntents, rerankedByIntent);

        if (allChunks.isEmpty()) {
            return snippetSection;
        }

        String body = formatEvidenceItems(allChunks, topK);
        return renderKbSection(snippetSection, body);
    }

    private String formatChunksWithoutIntent(Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (List<RetrievedChunk> list : rerankedByIntent.values()) {
            if (CollUtil.isEmpty(list)) {
                continue;
            }
            for (RetrievedChunk chunk : list) {
                chunks.add(chunk);
            }
        }
        if (chunks.isEmpty()) {
            return "";
        }

        String body = formatEvidenceItems(chunks, topK);
        return renderKbSection("", body);
    }

    private List<RetrievedChunk> flattenByIntentOrder(List<NodeScore> kbIntents,
                                                      Map<String, List<RetrievedChunk>> rerankedByIntent) {
        List<RetrievedChunk> chunks = new ArrayList<>();
        Set<String> visitedKeys = new LinkedHashSet<>();
        if (CollUtil.isNotEmpty(kbIntents)) {
            for (NodeScore score : kbIntents) {
                IntentNode node = score == null ? null : score.getNode();
                String key = node == null ? null : node.getId();
                if (StrUtil.isBlank(key) || !visitedKeys.add(key)) {
                    continue;
                }
                List<RetrievedChunk> list = rerankedByIntent.get(key);
                if (CollUtil.isNotEmpty(list)) {
                    chunks.addAll(list);
                }
            }
        }
        rerankedByIntent.keySet().stream()
                .filter(StrUtil::isNotBlank)
                .filter(key -> !visitedKeys.contains(key))
                .sorted()
                .forEach(key -> {
                    List<RetrievedChunk> list = rerankedByIntent.get(key);
                    if (CollUtil.isNotEmpty(list)) {
                        chunks.addAll(list);
                    }
                });
        return chunks;
    }

    @Override
    public String formatMcpContext(Map<String, List<CallToolResult>> toolResults,
                                   List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(toolResults)) {
            return "";
        }
        if (CollUtil.isEmpty(mcpIntents)) {
            return mergeAllResultsToText(toolResults);
        }

        Map<String, IntentNode> toolToIntent = new LinkedHashMap<>();
        for (NodeScore ns : mcpIntents) {
            IntentNode node = ns.getNode();
            if (node == null || StrUtil.isBlank(node.getMcpToolId())) {
                continue;
            }
            toolToIntent.putIfAbsent(node.getMcpToolId(), node);
        }

        return toolToIntent.entrySet().stream()
                .map(entry -> {
                    List<CallToolResult> results = toolResults.get(entry.getKey());
                    if (CollUtil.isEmpty(results)) {
                        return "";
                    }
                    IntentNode node = entry.getValue();
                    String snippet = StrUtil.emptyIfNull(node.getPromptSnippet()).trim();
                    String body = mergeResultsToText(results);
                    if (StrUtil.isBlank(body)) {
                        return "";
                    }
                    String snippetSection = StrUtil.isNotBlank(snippet)
                            ? templateLoader.renderSection(CONTEXT_FORMAT_PATH, "mcp-intent-rules", Map.of("rules", snippet))
                            : "";
                    return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "mcp-section", Map.of(
                            "snippet_section", snippetSection,
                            "body", body
                    ));
                })
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n\n"));
    }

    // ==================== 工具方法 ====================

    private String renderKbSection(String snippetSection, String chunksBody) {
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-section", Map.of(
                "snippet_section", snippetSection,
                "chunks_body", chunksBody
        ));
    }

    private String renderSnippetRules(String snippet) {
        if (StrUtil.isBlank(snippet)) {
            return "";
        }
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "snippet-rules", Map.of("rules", snippet));
    }

    private String formatEvidenceItems(List<RetrievedChunk> chunks, int topK) {
        List<RetrievedChunk> limitedChunks = deduplicateAndLimit(chunks, topK);
        if (CollUtil.isEmpty(limitedChunks)) {
            return "";
        }
        List<EvidenceMetadata> metadata = evidenceMetadataResolver.resolve(limitedChunks);
        return IntStream.range(0, limitedChunks.size())
                .mapToObj(index -> renderEvidenceItem(limitedChunks.get(index), metadataAt(metadata, index)))
                .collect(Collectors.joining("\n\n"));
    }

    private EvidenceMetadata metadataAt(List<EvidenceMetadata> metadata, int index) {
        if (metadata == null || index >= metadata.size() || metadata.get(index) == null) {
            return EvidenceMetadata.unknown(index + 1, null);
        }
        return metadata.get(index);
    }

    private String renderEvidenceItem(RetrievedChunk chunk, EvidenceMetadata metadata) {
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-evidence-item", Map.of(
                "evidence_id", EvidenceMetadata.blankToUnknown(metadata.evidenceId()),
                "doc_id", EvidenceMetadata.blankToUnknown(metadata.docId()),
                "doc_name", EvidenceMetadata.blankToUnknown(metadata.docName()),
                "chunk_id", EvidenceMetadata.blankToUnknown(metadata.chunkId()),
                "chunk_index", metadata.chunkIndex() == null ? EvidenceMetadata.UNKNOWN : String.valueOf(metadata.chunkIndex()),
                "text", StrUtil.emptyIfNull(chunk == null ? null : chunk.getText())
        ));
    }

    private List<RetrievedChunk> deduplicateAndLimit(List<RetrievedChunk> chunks, int topK) {
        if (CollUtil.isEmpty(chunks)) {
            return List.of();
        }
        int limit = topK > 0 ? topK : Integer.MAX_VALUE;
        Set<String> seen = new LinkedHashSet<>();
        List<RetrievedChunk> output = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String key = chunkKey(chunk);
            if (!seen.add(key)) {
                continue;
            }
            output.add(chunk);
            if (output.size() >= limit) {
                break;
            }
        }
        return output;
    }

    private String chunkKey(RetrievedChunk chunk) {
        if (StrUtil.isNotBlank(chunk.getId())) {
            return "id:" + chunk.getId();
        }
        return "text:" + StrUtil.emptyIfNull(chunk.getText());
    }

    private String mergeAllResultsToText(Map<String, List<CallToolResult>> toolResults) {
        List<CallToolResult> allResults = toolResults.values().stream()
                .flatMap(List::stream)
                .toList();
        return mergeResultsToText(allResults);
    }

    /**
     * 将多个 CallToolResult 合并为文本
     */
    private String mergeResultsToText(List<CallToolResult> results) {
        if (CollUtil.isEmpty(results)) {
            return "";
        }

        List<String> successTexts = new ArrayList<>();
        List<String> errorTexts = new ArrayList<>();

        for (CallToolResult result : results) {
            boolean isError = result.isError() != null && result.isError();
            String text = extractTextContent(result);
            if (!isError && text != null) {
                successTexts.add(text);
            } else if (isError && text != null) {
                errorTexts.add("- 工具调用失败: " + text);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String text : successTexts) {
            sb.append(text).append("\n\n");
        }

        if (CollUtil.isNotEmpty(errorTexts)) {
            String errorList = String.join("\n", errorTexts);
            sb.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, "mcp-error", Map.of("error_list", errorList)));
        }

        return sb.toString().trim();
    }

    private String extractTextContent(CallToolResult result) {
        if (result == null || result.content() == null) {
            return null;
        }
        List<String> texts = result.content().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .toList();
        return texts.isEmpty() ? null : String.join("\n", texts);
    }
}
