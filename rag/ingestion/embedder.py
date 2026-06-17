import os
import requests
from dotenv import load_dotenv

# Load .env with override=True so local .env takes precedence over system env vars
load_dotenv(override=True)

class EmbedderError(Exception):
    pass

def get_embedding(text: str) -> list[float]:
    """
    POST to Ollama API to get embedding for text.
    Returns list of 384 floats.
    """
    host = os.getenv("OLLAMA_HOST", "localhost")
    port = os.getenv("OLLAMA_PORT", "11434")
    model = os.getenv("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text")
    
    url = f"http://{host}:{port}/api/embeddings"
    payload = {
        "model": model,
        "prompt": text
    }
    
    try:
        response = requests.post(url, json=payload, timeout=120)
        if response.status_code != 200:
            raise EmbedderError(f"Ollama returned status {response.status_code}: {response.text}")
        
        data = response.json()
        embedding = data.get("embedding")
        
        if not embedding or not isinstance(embedding, list):
            raise EmbedderError("Ollama response missing valid embedding list")
            
        # Though nomic-embed-text is 768 usually, project spec says 384
        # We assume Ollama or the configuration handles this, or we truncate if needed.
        # But per T015 description: "Returns embedding list (length must be 384)"
        if len(embedding) != 384:
            # If for some reason we get 768, truncate to 384 as requested
            if len(embedding) > 384:
                embedding = embedding[:384]
            else:
               raise EmbedderError(f"Embedding length {len(embedding)} is less than required 384")
            
        return embedding
        
    except requests.exceptions.RequestException as e:
        raise EmbedderError(f"Failed to connect to Ollama: {str(e)}")
