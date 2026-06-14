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

import com.zimingd.ai.ragent.framework.convention.RetrievedChunk;
import com.zimingd.ai.ragent.rag.config.SearchChannelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PostgresKeywordSearchChannel implements SearchChannel {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_.-]*");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d");
    private static final Pattern LETTER_OR_SEPARATOR_PATTERN = Pattern.compile("[\\p{L}_.-]");
    private static final Pattern ASCII_KEYWORD_PATTERN = Pattern.compile("(?i)(?<![A-Z0-9])[A-Z][A-Z0-9_.-]*(?![A-Z0-9])");
    private static final Pattern TRAILING_SEPARATOR_PATTERN = Pattern.compile("[_.-]+$");
    private static final String SEARCH_SQL = """
            SELECT id,
                   content,
                   CASE WHEN ? AND identifier_tokens && ?::text[] THEN 1 ELSE 0 END AS exact_match,
                   ts_rank_cd(search_vector, websearch_to_tsquery('simple', ?), 32) AS text_rank
              FROM t_knowledge_vector
             WHERE metadata->>'collection_name' = ANY (?::text[])
               AND ((? AND identifier_tokens && ?::text[])
                    OR search_vector @@ websearch_to_tsquery('simple', ?))
             ORDER BY exact_match DESC, text_rank DESC, id
             LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "PostgresKeywordSearch";
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getKeywordPg().isEnabled()
                && context.getRetrievalScope() != null
                && !context.getRetrievalScope().collectionNames().isEmpty();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            String query = context.getMainQuestion();
            List<String> identifiers = extractIdentifierTokens(query);
            List<String> collections = context.getRetrievalScope().collectionNames();
            int topK = context.getTopK() * properties.getChannels().getKeywordPg().getTopKMultiplier();
            boolean ordinaryFtsEnabled = shouldRunOrdinaryFts(query);
            List<RetrievedChunk> chunks = ordinaryFtsEnabled || !identifiers.isEmpty()
                    ? executeSearch(query, identifiers, collections, topK)
                    : List.of();
            long latency = System.currentTimeMillis() - startTime;
            log.info("PostgreSQL keyword retrieval completed, chunks={}, identifiers={}, latencyMs={}",
                    chunks.size(), identifiers.size(), latency);
            List<String> exactChunkIds = identifiers.isEmpty()
                    ? List.of()
                    : chunks.stream()
                            .filter(chunk -> chunk.getScore() != null && chunk.getScore() >= 1000.0F)
                            .map(RetrievedChunk::getId)
                            .toList();
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_PG)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .metadata(Map.of(
                            "identifierCount", identifiers.size(),
                            "exactIdentifierChunkIds", exactChunkIds,
                            "collectionCount", collections.size(),
                            "ordinaryFtsEnabled", ordinaryFtsEnabled
                    ))
                    .build();
        } catch (Exception e) {
            log.error("PostgreSQL keyword retrieval failed", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_PG)
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
        return SearchChannelType.KEYWORD_PG;
    }

    private List<RetrievedChunk> executeSearch(String query,
                                               List<String> identifiers,
                                               List<String> collections,
                                               int topK) {
        return jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(SEARCH_SQL);
            boolean hasIdentifiers = !identifiers.isEmpty();
            Array identifierArray = connection.createArrayOf("text", identifiers.toArray());
            Array collectionArray = connection.createArrayOf("text", collections.toArray());
            statement.setBoolean(1, hasIdentifiers);
            statement.setArray(2, identifierArray);
            statement.setString(3, query);
            statement.setArray(4, collectionArray);
            statement.setBoolean(5, hasIdentifiers);
            statement.setArray(6, identifierArray);
            statement.setString(7, query);
            statement.setInt(8, topK);
            return statement;
        }, (rs, rowNum) -> RetrievedChunk.builder()
                .id(rs.getString("id"))
                .text(rs.getString("content"))
                .score(rs.getFloat("exact_match") * 1000.0F + rs.getFloat("text_rank"))
                .build());
    }

    static List<String> extractIdentifierTokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(query);
        while (matcher.find()) {
            String token = TRAILING_SEPARATOR_PATTERN.matcher(
                    matcher.group().toLowerCase(Locale.ROOT)
            ).replaceAll("");
            if (DIGIT_PATTERN.matcher(token).find()
                    && LETTER_OR_SEPARATOR_PATTERN.matcher(token).find()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    boolean shouldRunOrdinaryFts(String query) {
        return !properties.getChannels().getKeywordPg().isOrdinaryFtsConditional()
                || hasAsciiKeyword(query);
    }

    static boolean hasAsciiKeyword(String query) {
        return query != null && ASCII_KEYWORD_PATTERN.matcher(query).find();
    }
}
