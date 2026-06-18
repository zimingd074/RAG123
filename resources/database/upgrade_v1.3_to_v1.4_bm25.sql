CREATE EXTENSION IF NOT EXISTS pg_search;

CREATE INDEX IF NOT EXISTS idx_kv_bm25
    ON t_knowledge_vector
    USING bm25 (
        id,
        (content::pdb.jieba),
        ((metadata->>'collection_name')::pdb.literal('alias=collection_name'))
    )
    WITH (key_field='id');
