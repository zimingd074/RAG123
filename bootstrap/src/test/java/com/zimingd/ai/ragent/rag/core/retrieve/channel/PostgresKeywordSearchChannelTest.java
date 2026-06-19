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

import com.zimingd.ai.ragent.rag.config.SearchChannelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresKeywordSearchChannelTest {

    @Test
    void extractsProductVersionAndErrorIdentifiers() {
        List<String> tokens = PostgresKeywordSearchChannel.extractIdentifierTokens(
                "Check AB-1234 on v2.3.1, error ERR_001. and plain words"
        );

        assertEquals(List.of("ab-1234", "v2.3.1", "err_001"), tokens);
    }

    @Test
    void ignoresWordsWithoutDigits() {
        assertEquals(List.of(), PostgresKeywordSearchChannel.extractIdentifierTokens(
                "printer cartridge replacement"
        ));
    }

    @Test
    void detectsAsciiKeywordsForConditionalKeywordTextSearch() {
        assertTrue(PostgresKeywordSearchChannel.hasAsciiKeyword("APP 里怎么查清扫记录"));
        assertTrue(PostgresKeywordSearchChannel.hasAsciiKeyword("RedmiBook Pro 14 适合剪视频吗"));
        assertFalse(PostgresKeywordSearchChannel.hasAsciiKeyword("现在买和等双十一买哪个划算"));
    }

    @Test
    void conditionalKeywordTextSearchSkipsPureChineseQueries() {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().getKeywordPg().setKeywordTextSearchConditional(true);
        PostgresKeywordSearchChannel channel = new PostgresKeywordSearchChannel(
                new JdbcTemplate(),
                properties
        );

        assertFalse(channel.shouldRunKeywordTextSearch("现在买和等双十一买哪个划算"));
        assertTrue(channel.shouldRunKeywordTextSearch("APP 里怎么查清扫记录"));
    }

    @Test
    void defaultKeywordSearchRunsForPureChineseQueries() {
        PostgresKeywordSearchChannel channel = new PostgresKeywordSearchChannel(
                new JdbcTemplate(),
                new SearchChannelProperties()
        );

        assertTrue(channel.shouldRunKeywordTextSearch("现在买和等双十一买哪个划算"));
    }

    @Test
    void keywordSearchSqlUsesParadeDbBm25() {
        String sql = PostgresKeywordSearchChannel.searchSql();

        assertTrue(sql.contains("pdb.score(id) AS rank_score"));
        assertTrue(sql.contains("content ||| ?"));
        assertFalse(sql.contains("ts_rank_cd"));
        assertFalse(sql.contains("websearch_to_tsquery"));
    }

    @Test
    void legacyKeywordSearchSqlUsesPostgresFts() {
        String sql = PostgresKeywordSearchChannel.ftsSearchSql();

        assertTrue(sql.contains("ts_rank_cd"));
        assertTrue(sql.contains("websearch_to_tsquery('simple', ?)"));
        assertTrue(sql.contains("search_vector @@ q.query"));
        assertFalse(sql.contains("pdb.score"));
        assertFalse(sql.contains("content ||| ?"));
    }

    @Test
    void defaultsToBm25Ranking() {
        PostgresKeywordSearchChannel channel = new PostgresKeywordSearchChannel(
                new JdbcTemplate(),
                new SearchChannelProperties()
        );

        assertEquals("bm25", channel.keywordRanking());
    }

    @Test
    void canSwitchToLegacyFtsRanking() {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().getKeywordPg().setRanking("fts");
        PostgresKeywordSearchChannel channel = new PostgresKeywordSearchChannel(
                new JdbcTemplate(),
                properties
        );

        assertEquals("fts", channel.keywordRanking());
    }
}
