from pathlib import Path
from unittest.mock import MagicMock, patch

from click.testing import CliRunner

from rag.ingestion.chunker import chunk_document
from rag.ingestion.ingest import _extract_page_title, main
from rag.ingestion.space_weights import DEFAULT_WEIGHT, get_weight


def test_get_weight_spl():
    assert get_weight("SPL") == 1.0


def test_get_weight_siccicd():
    assert get_weight("SICCICD") == 0.9


def test_get_weight_case_insensitive():
    assert get_weight("spl") == 1.0


def test_get_weight_unknown():
    assert get_weight("unknown-space") == DEFAULT_WEIGHT


def test_chunk_document_extra_metadata():
    chunks = chunk_document(
        "# Title\nContent",
        "f.html",
        "confluence",
        extra_metadata={"page_title": "PT", "space_name": "SPL", "space_weight": 1.0},
    )

    assert chunks
    for chunk in chunks:
        assert chunk["metadata"]["page_title"] == "PT"
        assert chunk["metadata"]["space_name"] == "SPL"
        assert chunk["metadata"]["space_weight"] == 1.0
        assert "section_title" in chunk["metadata"]
        assert "chunk_index" in chunk["metadata"]


def test_chunk_document_no_extra_metadata():
    chunks = chunk_document("## Header\nContent", "f.html", "confluence")

    assert chunks
    assert chunks[0]["metadata"]["doc_type"] == "confluence"
    assert chunks[0]["metadata"]["section_title"] == "Header"


def test_html_title_extraction_from_span():
    html = """
    <html>
      <body>
        <h1 id="title-heading">
          <span id="title-text">SOLUTIONS PRODUCT LINE : Page Title</span>
        </h1>
      </body>
    </html>
    """

    assert _extract_page_title(html, "182772268.html") == "Page Title"


def test_html_title_fallback_to_title_tag():
    html = "<html><head><title>SPACE : Page Title</title></head><body></body></html>"

    assert _extract_page_title(html, "182772268.html") == "Page Title"


def test_html_title_fallback_to_filename():
    html = "<html><body><p>No title metadata here</p></body></html>"

    assert _extract_page_title(html, "182772268.html") == "182772268"


def test_ingest_confluence_cli_flow():
    runner = CliRunner()

    with patch("rag.ingestion.ingest.get_embedding") as mock_embed, \
         patch("rag.ingestion.ingest.connect") as mock_connect, \
         patch("rag.ingestion.ingest.insert_chunk") as mock_insert:
        mock_embed.return_value = [0.1] * 384
        mock_conn = MagicMock()
        mock_connect.return_value = mock_conn

        with runner.isolated_filesystem():
            input_root = Path("input")
            (input_root / "SPL").mkdir(parents=True)
            (input_root / "SPL" / "attachments").mkdir(parents=True)
            (input_root / "SPL" / "123.html").write_text(
                """
                <html>
                  <head><title>SPL : Operations Runbook</title></head>
                  <body><h1><span id="title-text">SPL : Operations Runbook</span></h1><p>Hello world</p></body>
                </html>
                """,
                encoding="utf-8",
            )
            (input_root / "SPL" / "attachments" / "ignored.html").write_text(
                "<html><body>ignored</body></html>",
                encoding="utf-8",
            )

            result = runner.invoke(main, ["--source", "confluence", "--input", str(input_root)])

        assert result.exit_code == 0
        assert mock_insert.call_count == 1
        insert_args = mock_insert.call_args[0]
        assert insert_args[2] == "embeddings_confluence_knowledge"
        assert insert_args[1]["metadata"]["space_name"] == "SPL"
        assert insert_args[1]["metadata"]["page_title"] == "Operations Runbook"
        assert insert_args[1]["metadata"]["space_weight"] == 1.0
