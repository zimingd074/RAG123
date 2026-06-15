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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreAdmin implements VectorStoreAdmin {

    private final JdbcTemplate jdbcTemplate;
    private final RAGDefaultProperties ragDefaultProperties;

    @PostConstruct
    void validateConfiguredDimension() {
        String relation = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.t_knowledge_vector')",
                String.class);
        if (relation == null) {
            log.warn("跳过 pgvector 维度校验: t_knowledge_vector 尚未创建");
            return;
        }

        String columnType = jdbcTemplate.queryForObject(
                "SELECT format_type(a.atttypid, a.atttypmod) "
                        + "FROM pg_attribute a "
                        + "WHERE a.attrelid = 'public.t_knowledge_vector'::regclass "
                        + "AND a.attname = 'embedding' AND NOT a.attisdropped",
                String.class);
        int configuredDimension = ragDefaultProperties.getDimension();
        String expectedType = "vector(" + configuredDimension + ")";
        if (!expectedType.equals(columnType)) {
            throw new IllegalStateException(
                    "pgvector 维度与配置不一致: column=" + columnType
                            + ", configured=" + configuredDimension);
        }
    }

    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = 'public' "
                        + "AND tablename = 't_knowledge_vector' "
                        + "AND indexdef ILIKE '%USING hnsw (embedding vector_cosine_ops)%'",
                Integer.class);

        if (count != null && count > 0) {
            log.debug("HNSW 索引已存在");
            return;
        }

        int dimension = ragDefaultProperties.getDimension();
        if (dimension > 2000) {
            throw new IllegalStateException(
                    "pgvector vector HNSW 仅支持最多 2000 维，当前配置为 " + dimension
                            + "；请使用 halfvec、子向量索引或降低输出维度");
        }
        log.info("创建pgvector HNSW索引，维度: {}", dimension);
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_kv_embedding "
                        + "ON t_knowledge_vector USING hnsw (embedding vector_cosine_ops)");
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector LIMIT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
