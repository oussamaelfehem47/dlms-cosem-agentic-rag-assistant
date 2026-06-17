import re


def chunk_document(
    text: str,
    source_file: str,
    doc_type: str,
    extra_metadata: dict | None = None,
) -> list[dict]:
    """
    Splits markdown text into header-aware chunks.
    Split on ## and ### headers first.
    Within each section, applies sliding window 800 chars / 100 overlap.
    """
    # Split by markdown headers (treating #, ##, ### as section starts)
    # We use a regex that captures the header line and then the following content
    pattern = r'(^#+ .*$)'
    parts = re.split(pattern, text, flags=re.MULTILINE)
    
    chunks = []
    current_section_title = "General"
    chunk_index = 0
    
    # re.split with capturing group returns [non-match, match, non-match, match, ...]
    # If the text starts with a header, the first element might be empty.
    
    i = 0
    while i < len(parts):
        part = parts[i]
        if not part.strip():
            i += 1
            continue
            
        if part.startswith("#"):
            current_section_title = part.lstrip("#").strip()
            i += 1
            # Next part should be the content of this section
            if i < len(parts):
                section_content = parts[i].strip()
                if section_content:
                    sub_chunks = _sliding_window(section_content, 800, 100)
                    for sc in sub_chunks:
                        chunks.append({
                            "content": sc,
                            "source_file": source_file,
                            "metadata": {
                                **(extra_metadata or {}),
                                "source_file": source_file,
                                "doc_type": doc_type,
                                "section_title": current_section_title,
                                "chunk_index": chunk_index,
                                "page_number": 0 # Placeholder, as page info needs Docling specific logic
                            }
                        })
                        chunk_index += 1
                i += 1
        else:
            # Content before any header
            section_content = part.strip()
            sub_chunks = _sliding_window(section_content, 800, 100)
            for sc in sub_chunks:
                chunks.append({
                    "content": sc,
                    "source_file": source_file,
                    "metadata": {
                        **(extra_metadata or {}),
                        "source_file": source_file,
                        "doc_type": doc_type,
                        "section_title": current_section_title,
                        "chunk_index": chunk_index,
                        "page_number": 0
                    }
                })
                chunk_index += 1
            i += 1
            
    return chunks

def _sliding_window(text: str, size: int, overlap: int) -> list[str]:
    """Simple sliding window splitter."""
    if len(text) <= size:
        return [text]
    
    results = []
    start = 0
    while start < len(text):
        end = start + size
        results.append(text[start:end])
        if end >= len(text):
            break
        start = end - overlap
        
        # Avoid infinite loop if overlap >= size
        if start >= end:
            start = end
            
    return results
