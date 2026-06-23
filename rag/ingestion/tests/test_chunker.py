import pytest
from rag.ingestion.chunker import chunk_document

def test_chunk_document_header_aware():
    text = "## Section 1\nContent of section 1. More text to reach some size.\n### Sub 1.1\nSubcontent here."
    chunks = chunk_document(text, "test.md", "dlms")
    
    assert len(chunks) >= 2
    # Check if first chunk contains Section 1 title from header
    assert any("Section 1" in c["metadata"]["section_title"] for c in chunks)

def test_chunk_document_metadata():
    text = "## Header\nContent"
    chunks = chunk_document(text, "manual.pdf", "dlms")
    
    assert len(chunks) > 0
    meta = chunks[0]["metadata"]
    assert meta["source_file"] == "manual.pdf"
    assert meta["doc_type"] == "dlms"
    assert "chunk_index" in meta

def test_chunk_document_size_limit():
    # Long text without headers
    text = "long text " * 500 
    chunks = chunk_document(text, "long.txt", "dlms")
    
    for chunk in chunks:
        assert len(chunk["content"]) <= 900 # 800 base + metadata headers/buffer
