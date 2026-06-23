import pytest
import json
import hashlib
from unittest.mock import MagicMock, patch
from rag.ingestion.db import insert_chunk

def test_insert_chunk_valid():
    mock_conn = MagicMock()
    mock_cur = mock_conn.cursor.return_value.__enter__.return_value
    
    chunk = {
        "content": "test content",
        "embedding": [0.1] * 384,
        "metadata": {"doc_type": "test"},
        "source_file": "test.pdf"
    }
    
    insert_chunk(mock_conn, chunk, "embeddings_dlms_knowledge")
    
    # Verify cursor executed the INSERT
    assert mock_cur.execute.called
    sql = mock_cur.execute.call_args[0][0]
    params = mock_cur.execute.call_args[0][1]
    
    assert "INSERT INTO embeddings_dlms_knowledge" in sql
    assert "ON CONFLICT (source_file, content_hash) DO NOTHING" in sql
    
    # Verify content_hash calculation (SHA-256 of "test content")
    expected_hash = hashlib.sha256("test content".encode()).hexdigest()
    assert params[1] == expected_hash # Assuming params[1] is content_hash
