import pytest
from unittest.mock import MagicMock, patch
from click.testing import CliRunner
from rag.ingestion.ingest import main

def test_ingest_cli_flow():
    """Integration style test for the ingest CLI flow using mocks."""
    runner = CliRunner()
    
    with patch("rag.ingestion.ingest.DocumentConverter") as mock_conv, \
         patch("rag.ingestion.ingest.get_embedding") as mock_embed, \
         patch("rag.ingestion.ingest.connect") as mock_db, \
         patch("rag.ingestion.ingest.insert_chunk") as mock_insert:
        
        # Mock Docling conversion result
        mock_result = MagicMock()
        mock_result.document.export_to_markdown.return_value = "## Section 1\nThis is a test document content."
        mock_conv.return_value.convert.return_value = mock_result
        
        # Mock Ollama embedding
        mock_embed.return_value = [0.1] * 384
        
        # Mock DB connection
        mock_conn = MagicMock()
        mock_db.return_value = mock_conn
        
        # Run CLI in an isolated filesystem
        with runner.isolated_filesystem():
            with open("test_document.pdf", "w") as f:
                f.write("fake pdf content")
            
            result = runner.invoke(main, ["--source", "dlms", "--input", "test_document.pdf"])
            
            # Assertions
            assert result.exit_code == 0
            assert "Processing" in result.output
            assert "Ingestion complete" in result.output
            
            # Verify dependencies were called
            assert mock_conv.return_value.convert.called
            assert mock_embed.called
            assert mock_insert.call_count == 1
            
            # Verify the collection mapping and chunk data
            insert_args = mock_insert.call_args[0]
            chunk_data = insert_args[1]
            assert insert_args[2] == "embeddings_dlms_knowledge"
            assert chunk_data["content"] == "This is a test document content."
            assert chunk_data["metadata"]["section_title"] == "Section 1"

def test_ingest_invalid_source():
    runner = CliRunner()
    with runner.isolated_filesystem():
        with open("test.pdf", "w") as f: f.write("test")
        result = runner.invoke(main, ["--source", "invalid", "--input", "test.pdf"])
        assert result.exit_code != 0
        assert "Invalid value for '--source'" in result.output
