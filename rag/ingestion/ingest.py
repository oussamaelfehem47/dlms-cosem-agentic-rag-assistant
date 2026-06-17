import click
import os
import sys
from pathlib import Path

from bs4 import BeautifulSoup

from rag.ingestion.chunker import chunk_document
from rag.ingestion.embedder import get_embedding, EmbedderError
from rag.ingestion.db import connect, insert_chunk
from rag.ingestion.space_weights import get_weight

DocumentConverter = None


def _get_document_converter():
    global DocumentConverter
    if DocumentConverter is None:
        try:
            from docling.document_converter import DocumentConverter as docling_converter
        except ImportError:
            return None
        DocumentConverter = docling_converter
    return DocumentConverter


def _find_dlms_files(input_path: str) -> list[str]:
    if os.path.isdir(input_path):
        files = []
        for file_name in os.listdir(input_path):
            if file_name.endswith((".pdf", ".docx", ".md", ".markdown")):
                files.append(os.path.join(input_path, file_name))
        return files
    return [input_path]


def _find_confluence_files(input_path: str) -> list[tuple[str, str]]:
    root = Path(input_path)
    if root.is_file():
        return [(str(root), root.parent.name)] if root.suffix.lower() == ".html" else []

    files: list[tuple[str, str]] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [name for name in dirnames if name not in ("attachments", "images")]
        relative = os.path.relpath(dirpath, root)
        if relative == ".":
            continue

        space_name = relative.split(os.sep)[0]
        for filename in filenames:
            if filename.lower().endswith(".html"):
                files.append((os.path.join(dirpath, filename), space_name))
    return files


def _extract_page_title(html: str, filename: str) -> str:
    soup = BeautifulSoup(html, "html.parser")

    title_node = soup.find("span", {"id": "title-text"})
    if title_node is None:
        title_node = soup.find("title")

    raw_title = title_node.get_text(strip=True) if title_node is not None else ""
    if raw_title:
        parts = raw_title.split(" : ")
        candidate = parts[-1].strip()
        if candidate:
            return candidate

    return os.path.splitext(filename)[0]


def _extract_confluence_text(html: str) -> str:
    soup = BeautifulSoup(html, "html.parser")
    content_root = soup.find("div", {"id": "content"}) or soup.body or soup
    return content_root.get_text(separator="\n", strip=True)


def _read_dlms_markdown(file_path: str, converter):
    if file_path.endswith((".md", ".markdown")):
        with open(file_path, "r", encoding="utf-8") as handle:
            return handle.read(), converter

    converter_class = _get_document_converter()
    if converter_class is None:
        raise RuntimeError("Docling not installed. Cannot process PDF/DOCX files.")

    if converter is None:
        converter = converter_class()

    result = converter.convert(file_path)
    if hasattr(result, "document"):
        return result.document.export_to_markdown(), converter
    return str(result), converter

@click.command()
@click.option('--source', type=click.Choice(['dlms', 'confluence']), required=True, 
              help="The source collection to ingest into.")
@click.option('--input', 'input_path', type=click.Path(exists=True), required=True, 
              help="Path to the file or directory to process.")
@click.option('--host', default=None, help="Optional database host override.")
@click.option('--batch-size', default=1, help="Number of concurrent embeddings (placeholder for future).")
def main(source, input_path, host, batch_size):
    """
    DLMS RAG Ingestion Pipeline.
    Processes documents, chunks them, embeds them via Ollama,
    and stores them in the PostgreSQL PgVector store.
    """
    files = _find_confluence_files(input_path) if source == "confluence" else _find_dlms_files(input_path)

    if not files:
        click.echo("No supported files found to process.")
        return

    click.echo(f"Found {len(files)} files to process.")

    # Establish Database Connection
    try:
        conn = connect(host=host)
    except Exception as e:
        click.echo(f"Database connection failed: {e}", err=True)
        sys.exit(1)

    collection = "embeddings_dlms_knowledge" if source == "dlms" else "embeddings_confluence_knowledge"
    converter = None

    for item in files:
        if source == "confluence":
            file_path, space_name = item
            source_name = os.path.basename(file_path)
            click.echo(f"\nProcessing {source_name}...")

            try:
                html = Path(file_path).read_text(encoding="utf-8")
                page_title = _extract_page_title(html, source_name)
                raw_text = _extract_confluence_text(html)
            except Exception as error:
                click.echo(f"Warning: skipping {file_path}: {error}")
                continue

            if not raw_text.strip():
                click.echo(f"Warning: {file_path} resulted in empty text. Skipping.")
                continue

            chunks = chunk_document(
                raw_text,
                source_name,
                source,
                extra_metadata={
                    "space_name": space_name,
                    "page_title": page_title,
                    "space_weight": get_weight(space_name),
                },
            )
        else:
            file_path = item
            source_name = os.path.basename(file_path)
            click.echo(f"\nProcessing {source_name}...")

            try:
                markdown_text, converter = _read_dlms_markdown(file_path, converter)
            except Exception as error:
                click.echo(f"Error reading/parsing {file_path}: {error}", err=True)
                continue

            if not markdown_text.strip():
                click.echo(f"Warning: {file_path} resulted in empty text. Skipping.")
                continue

            chunks = chunk_document(markdown_text, source_name, source)

        total_chunks = len(chunks)
        click.echo(f"Split into {total_chunks} chunks.")

        # 3. Process chunks: Embed and Store
        ingested_count = 0
        with click.progressbar(chunks, label=f"Ingesting {source_name}") as bar:
            for chunk in bar:
                try:
                    # Get the 384-dim embedding from Ollama
                    embedding = get_embedding(chunk["content"])
                    chunk["embedding"] = embedding
                    
                    # Insert into PostgreSQL
                    insert_chunk(conn, chunk, collection)
                    ingested_count += 1
                except EmbedderError as e:
                    click.echo(f"\nWarning: Skipping chunk due to embedding failure: {e}")
                except Exception as e:
                    click.echo(f"\nError processing chunk: {e}")
                    
        click.echo(f"Successfully stored {ingested_count}/{total_chunks} chunks from {source_name}.")

    click.echo("\nIngestion complete.")
    conn.close()

if __name__ == '__main__':
    main()
