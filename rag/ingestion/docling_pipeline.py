"""
Docling-based document ingestion pipeline.
Parses PDFs → structured text + tables → saves to knowledge_packs/processed/
"""
from pathlib import Path
import json
from loguru import logger

from docling.document_converter import DocumentConverter

RAW_DIR = Path("knowledge_packs/raw")
PROCESSED_DIR = Path("knowledge_packs/processed")
PROCESSED_DIR.mkdir(parents=True, exist_ok=True)


def ingest_document(pdf_path: Path, doc_type: str) -> dict:
    """
    Convert a single PDF to structured content using Docling.
    doc_type: one of 'blue_book', 'green_book', 'siconia', 'obis', 'internal'
    """
    logger.info(f"Ingesting: {pdf_path.name} (type={doc_type})")

    converter = DocumentConverter()
    result = converter.convert(str(pdf_path))

    markdown_text = result.document.export_to_markdown()

    out_path = PROCESSED_DIR / f"{pdf_path.stem}_{doc_type}.md"
    out_path.write_text(markdown_text, encoding="utf-8")

    meta = {
        "source_file": pdf_path.name,
        "doc_type": doc_type,
        "output_file": out_path.name,
        "num_pages": len(result.document.pages) if hasattr(result.document, "pages") else 0,
    }
    meta_path = PROCESSED_DIR / f"{pdf_path.stem}_{doc_type}_meta.json"
    meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")

    logger.success(f"Saved: {out_path.name}")
    return meta


def _infer_doc_type(filename: str) -> str:
    lower_name = filename.lower()
    if "blue" in lower_name:
        return "blue_book"
    if "green" in lower_name:
        return "green_book"
    if "obis" in lower_name:
        return "obis"
    if "siconia" in lower_name:
        return "siconia"
    return "internal"


def ingest_all() -> list[dict]:
    """Run ingestion for all documents in the raw folder."""
    results: list[dict] = []

    for pdf_path in sorted(RAW_DIR.glob("*.pdf")):
        doc_type = _infer_doc_type(pdf_path.name)
        meta = ingest_document(pdf_path, doc_type)
        results.append(meta)

    siconia_dir = RAW_DIR / "siconia"
    if siconia_dir.exists():
        for pdf_path in sorted(siconia_dir.glob("*.pdf")):
            meta = ingest_document(pdf_path, "siconia")
            results.append(meta)

    logger.info(f"Ingestion complete. Processed {len(results)} documents.")
    return results


if __name__ == "__main__":
    ingest_all()
