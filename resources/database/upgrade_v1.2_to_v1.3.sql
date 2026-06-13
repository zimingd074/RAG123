ALTER TABLE t_knowledge_vector
    ADD COLUMN IF NOT EXISTS search_vector tsvector
        GENERATED ALWAYS AS (
            to_tsvector('simple', coalesce(content, ''))
        ) STORED;

ALTER TABLE t_knowledge_vector
    ADD COLUMN IF NOT EXISTS identifier_tokens TEXT[]
        GENERATED ALWAYS AS (
            regexp_split_to_array(
                regexp_replace(
                    regexp_replace(
                        lower(coalesce(content, '')),
                        '(^|[^[:alnum:]_.-])[_.-]+',
                        '\1',
                        'g'
                    ),
                    '[_.-]+($|[^[:alnum:]_.-])',
                    '\1',
                    'g'
                ),
                '[^[:alnum:]_.-]+'
            )
        ) STORED;

CREATE INDEX IF NOT EXISTS idx_kv_search_vector
    ON t_knowledge_vector USING gin(search_vector);

CREATE INDEX IF NOT EXISTS idx_kv_identifier_tokens
    ON t_knowledge_vector USING gin(identifier_tokens);

CREATE INDEX IF NOT EXISTS idx_kv_collection_name
    ON t_knowledge_vector ((metadata->>'collection_name'));
