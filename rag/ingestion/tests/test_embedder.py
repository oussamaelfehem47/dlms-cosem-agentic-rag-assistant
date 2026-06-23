import pytest
import requests
from unittest.mock import MagicMock, patch
from rag.ingestion.embedder import get_embedding, EmbedderError

def test_get_embedding_success():
    with patch("requests.post") as mock_post:
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"embedding": [0.1] * 384}
        mock_post.return_value = mock_response
        
        emb = get_embedding("test text")
        assert len(emb) == 384
        assert emb[0] == 0.1

def test_get_embedding_failure():
    with patch("requests.post") as mock_post:
        mock_response = MagicMock()
        mock_response.status_code = 500
        mock_post.return_value = mock_response
        
        with pytest.raises(EmbedderError):
            get_embedding("test text")
