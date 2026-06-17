# DLMS RAG Ingestion Pipeline

This directory contains the Python-based ingestion pipeline for the DLMS/COSEM Knowledge Base.

## Installation

Requires Python 3.10+ and a running Ollama instance with `nomic-embed-text`.

```bash
cd rag/ingestion
python -m venv .venv
.venv\Scripts\activate  # Windows
pip install -r requirements.txt
```

## Quick Start

### Ingesting Pre-processed Markdown
If you have high-quality markdown exports (like those in `knowledge_packs/processed`):

```bash
python ingest.py --source dlms --input ../../knowledge_packs/processed/ --host localhost
```

### Ingesting Raw Documents (PDF/DOCX)
Uses Docling for structured parsing:

```bash
python ingest.py --source dlms --input ../documents/ --host localhost
```

## CLI Options

- `--source`: `dlms` or `confluence`. Determines which database table is used.
- `--input`: Path to a single file or a directory of files.
- `--host`: Database host (default: `localhost`). Use `postgres` if running from inside Docker.

## Technical Details

- **Parsing**: PDFs and DOCX are converted to Markdown using Docling.
- **Chunking**: Header-aware splitting followed by a sliding window (800 chars, 100 overlap).
- **Embedding**: Uses Ollama API (`/api/embeddings`) with the `nomic-embed-text` model (384 dimensions).
- **Storage**: PostgreSQL with `pgvector`.
- **Idempotency**: Chunks are deduplicated via `SHA-256(content)` and `source_file`. Re-running ingestion on the same files is safe and will not create duplicates.
