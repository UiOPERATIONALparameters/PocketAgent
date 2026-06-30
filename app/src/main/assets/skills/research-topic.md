# Skill: Research a Topic

## When to Use
The user wants to research a topic, find information, or compare options.

## Steps

### 1. Start with Web Search
Use `web_search` to find relevant pages.

### 2. Read the Best Results
Use `web_reader` to extract clean article text from the top 3-5 results.

### 3. Cross-Reference
Check multiple sources. Prefer official docs, academic papers, reputable news.

### 4. Synthesize
Write a clear summary with key findings, supporting details, and source URLs.

### 5. Save to File (optional)
```
file_write("downloads/research-report.md", "# Research: Topic\n\n...")
```

## Tips
- Use `web_reader` instead of `web_fetch` for articles (cleaner text)
- Don't trust a single source — cross-reference
