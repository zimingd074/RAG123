-- Static RAG evaluation improvements: one intent may search multiple KBs.
ALTER TABLE t_intent_node ADD COLUMN IF NOT EXISTS kb_ids TEXT;
ALTER TABLE t_intent_node ADD COLUMN IF NOT EXISTS collection_names TEXT;

UPDATE t_intent_node
SET kb_ids = CASE WHEN kb_id IS NULL THEN NULL ELSE json_build_array(kb_id)::text END,
    collection_names = CASE
        WHEN collection_name IS NULL THEN NULL
        ELSE json_build_array(collection_name)::text
    END
WHERE kb_ids IS NULL AND collection_names IS NULL;
