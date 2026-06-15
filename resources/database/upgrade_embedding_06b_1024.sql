-- Switch an existing pgvector installation from 1536 dimensions to the
-- default Qwen3-Embedding-0.6B 1024-dimensional vector space.
--
-- This intentionally removes all existing vectors. Embeddings from different
-- models or dimensions cannot be reused. Re-index every document after this
-- migration completes.

BEGIN;

DO $$
DECLARE
    index_record RECORD;
BEGIN
    FOR index_record IN
        SELECT indexname
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 't_knowledge_vector'
          AND indexdef ILIKE '%USING hnsw (embedding%'
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS %I', index_record.indexname);
    END LOOP;
END
$$;

TRUNCATE TABLE t_knowledge_vector;

ALTER TABLE t_knowledge_vector
    ALTER COLUMN embedding TYPE vector(1024)
    USING NULL::vector(1024);

UPDATE t_knowledge_base
SET embedding_model = 'qwen-emb-06b',
    update_time = CURRENT_TIMESTAMP
WHERE embedding_model IS DISTINCT FROM 'qwen-emb-06b';

CREATE INDEX idx_kv_embedding
    ON t_knowledge_vector
    USING hnsw (embedding vector_cosine_ops);

COMMIT;
