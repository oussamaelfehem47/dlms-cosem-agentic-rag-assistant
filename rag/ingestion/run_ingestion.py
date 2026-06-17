"""
Master ingestion script: Docling → Chunking → Embedding → ChromaDB
Run this ONCE to build the knowledge base.
"""
import sys
from loguru import logger

sys.path.insert(0, ".")

from rag.ingestion.docling_pipeline import ingest_all
from rag.ingestion.chunker import chunk_all_processed
from rag.embeddings.indexer import index_chunks


if __name__ == "__main__":
    logger.info("=== DLMS Knowledge Base Construction Started ===")
    logger.info("STEP 1: Document ingestion with Docling...")
    ingest_all()

    logger.info("STEP 2: Chunking documents...")
    chunks = chunk_all_processed()

    if not chunks:
        logger.error("No chunks generated. Check that PDFs exist in knowledge_packs/raw/")
        raise SystemExit(1)

    logger.info("STEP 3: Embedding and indexing into ChromaDB...")
    count = index_chunks(chunks)

    logger.success(f"=== Knowledge Base Ready: {count} vectors indexed ===")
