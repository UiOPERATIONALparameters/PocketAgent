# Skill: Summarize a Document

## When to Use
The user wants a summary of a long document, article, or file.

## Steps

### 1. Read the Document
```
file_read("path/to/document")
```
If the document is very long, read it in chunks using start_line and end_line.

### 2. Extract Key Points
Identify:
- Main thesis/argument (1-2 sentences)
- Key supporting points (3-5 bullet points)
- Important data/statistics
- Conclusions/recommendations

### 3. Write the Summary
Structure:
```
## Summary: [Document Title]

**Key Finding**: [1-2 sentence summary]

### Main Points
- Point 1
- Point 2
- Point 3

### Notable Details
- Statistic or quote
- Important caveat

### Conclusion
[1-2 sentence conclusion]
```

### 4. Save (optional)
```
file_write("downloads/summary.md", "# Summary of [Document]\n\n...")
```

## Tips
- Read in chunks if file is > 256KB (use start_line/end_line)
- Preserve important numbers and quotes
- Note the source document's perspective/bias
- Keep summary to 10-20% of original length
