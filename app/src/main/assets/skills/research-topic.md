# Skill: Research a Topic

## When to Use
The user wants to research a topic, find information, or compare options. This includes:
- "Research X and tell me about it"
- "What are the best Y options?"
- "Compare A vs B"
- "Find the latest news about Z"

## Steps

### 1. Start with Web Search
Use `web_search` to find relevant pages:
```
web_search("topic keywords")
```
Get 5-10 results. Look at titles and snippets for relevance.

### 2. Read the Best Results
Use `web_reader` to extract clean article text from the top 3-5 results:
```
web_reader("https://example.com/article")
```
This returns clean text without ads/navigation/scripts.

### 3. Cross-Reference
If sources disagree, search for more specific terms or look for authoritative sources:
- Official documentation
- Academic papers (search: "topic site:arxiv.org" or "topic site:scholar.google.com")
- Reputable news sources
- GitHub repositories for code-related topics

### 4. Synthesize
Write a clear summary that:
- Starts with the key finding (1-2 sentences)
- Provides supporting details (3-5 bullet points)
- Notes any disagreements between sources
- Includes source URLs

### 5. Save to File (optional)
If the user wants a document:
```
file_write("downloads/research-report.md", "# Research: Topic\n\n...")
```

## Tips
- Search with specific keywords, not full sentences
- Use `web_reader` instead of `web_fetch` for articles (cleaner text)
- Check the date of sources — prefer recent ones for tech topics
- For code: search GitHub, Stack Overflow, official docs
- For academic: search arxiv.org, Google Scholar
- Don't trust a single source — cross-reference
