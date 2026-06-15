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

package com.zimingd.ai.ragent.rag.core.vector;

import com.zimingd.ai.ragent.rag.config.RAGDefaultProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgVectorStoreAdminTest {

    @Test
    void startupValidationAcceptsMatchingColumnDimension() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RAGDefaultProperties properties = properties(1024);
        PgVectorStoreAdmin admin = new PgVectorStoreAdmin(jdbcTemplate, properties);
        when(jdbcTemplate.queryForObject(
                contains("to_regclass"), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn("t_knowledge_vector");
        when(jdbcTemplate.queryForObject(
                contains("format_type"), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn("vector(1024)");

        assertDoesNotThrow(admin::validateConfiguredDimension);
    }

    @Test
    void startupValidationRejectsMismatchedColumnDimension() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RAGDefaultProperties properties = properties(1024);
        PgVectorStoreAdmin admin = new PgVectorStoreAdmin(jdbcTemplate, properties);
        when(jdbcTemplate.queryForObject(
                contains("to_regclass"), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn("t_knowledge_vector");
        when(jdbcTemplate.queryForObject(
                contains("format_type"), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn("vector(1536)");

        assertThrows(
                IllegalStateException.class,
                admin::validateConfiguredDimension);
    }

    @Test
    void existingEmbeddingHnswPreventsDuplicateIndex() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorStoreAdmin admin =
                new PgVectorStoreAdmin(jdbcTemplate, properties(1024));
        when(jdbcTemplate.queryForObject(
                contains("USING hnsw"), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(1);

        admin.ensureVectorSpace(VectorSpaceSpec.builder().build());

        verify(jdbcTemplate, never()).execute(
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void vectorHnswRejectsDimensionsAboveLimit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorStoreAdmin admin =
                new PgVectorStoreAdmin(jdbcTemplate, properties(2048));
        when(jdbcTemplate.queryForObject(
                contains("USING hnsw"), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> admin.ensureVectorSpace(VectorSpaceSpec.builder().build()));
    }

    private static RAGDefaultProperties properties(int dimension) {
        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setDimension(dimension);
        return properties;
    }
}
