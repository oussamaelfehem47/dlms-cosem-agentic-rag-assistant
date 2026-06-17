import os
import hashlib
import json
import psycopg2
from dotenv import load_dotenv

load_dotenv()

def connect(host=None):
    """Returns a psycopg2 connection using .env vars."""
    conn = psycopg2.connect(
        host=host or os.getenv("DB_HOST", "localhost"),
        port=os.getenv("DB_PORT", "5432"),
        database=os.getenv("DB_NAME", "dlms_assistant"),
        user=os.getenv("DB_USERNAME", "postgres"),
        password=os.getenv("DB_PASSWORD", "postgres")
    )
    return conn

def insert_chunk(conn, chunk: dict, collection: str):
    """
    Inserts a chunk into the specified collection table with duplicate handling.
    
    chunk dict: {content, embedding, metadata, source_file}
    """
    content = chunk["content"]
    content_hash = hashlib.sha256(content.encode()).hexdigest()
    source_file = chunk.get("source_file", "unknown")
    embedding = chunk["embedding"]
    metadata = chunk.get("metadata", {})
    
    # Ensure collection name is safe (parameterized sql can't handle table names)
    if collection not in ["embeddings_dlms_knowledge", "embeddings_confluence_knowledge"]:
        raise ValueError(f"Invalid collection: {collection}")

    sql = f"""
    INSERT INTO {collection} (content, content_hash, source_file, embedding, metadata)
    VALUES (%s, %s, %s, %s, %s)
    ON CONFLICT (source_file, content_hash) DO NOTHING
    """
    
    with conn.cursor() as cur:
        try:
            cur.execute(sql, (
                content,
                content_hash,
                source_file,
                embedding,
                json.dumps(metadata)
            ))
            conn.commit()
        except Exception as e:
            conn.rollback()
            raise e
