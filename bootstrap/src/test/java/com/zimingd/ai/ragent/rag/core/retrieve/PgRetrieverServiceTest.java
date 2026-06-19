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

package com.zimingd.ai.ragent.rag.core.retrieve;

import com.zimingd.ai.ragent.infra.embedding.EmbeddingService;
import com.zimingd.ai.ragent.rag.config.RAGConfigProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class PgRetrieverServiceTest {

    @Test
    void usesConfiguredHnswEfSearchBeforeVectorQuery() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setPgHnswEfSearch(120);
        PgRetrieverService service = new PgRetrieverService(
                jdbcTemplate,
                mock(EmbeddingService.class),
                properties
        );

        service.retrieveByVector(new float[]{1.0F, 0.0F}, RetrieveRequest.builder()
                .collectionName("kb-test")
                .topK(5)
                .build());

        assertEquals("SET hnsw.ef_search = 120", jdbcTemplate.executedSql);
    }

    @Test
    void rejectsInvalidHnswEfSearch() {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setPgHnswEfSearch(0);
        PgRetrieverService service = new PgRetrieverService(
                new RecordingJdbcTemplate(),
                mock(EmbeddingService.class),
                properties
        );

        assertThrows(IllegalArgumentException.class, service::resolveHnswEfSearch);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private String executedSql;

        @Override
        public void execute(String sql) throws DataAccessException {
            this.executedSql = sql;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args)
                throws DataAccessException {
            return List.of();
        }
    }
}
