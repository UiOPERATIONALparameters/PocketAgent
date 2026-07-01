# Skill: Convert Files

## When to Use
The user wants to convert between file formats (e.g., CSV to JSON, Markdown to HTML, image format conversion).

## Prerequisites
- Linux environment installed

## Common Conversions

### CSV to JSON
```bash
pkg install python
python3 -c "
import csv, json
with open('input.csv') as f:
    rows = list(csv.DictReader(f))
print(json.dumps(rows, indent=2))
" > output.json
```

### Markdown to HTML
```bash
pip install markdown
python3 -c "
import markdown
with open('input.md') as f:
    print(markdown.markdown(f.read()))
" > output.html
```

### Image Format Conversion
```bash
pkg install imagemagick
convert input.png output.jpg
convert input.jpg -resize 50% output_small.jpg
```

### JSON to CSV
```bash
python3 -c "
import json, csv
with open('input.json') as f:
    data = json.load(f)
with open('output.csv', 'w') as f:
    writer = csv.DictWriter(f, fieldnames=data[0].keys())
    writer.writeheader()
    writer.writerows(data)
"
```

### Video Format Conversion
```bash
pkg install ffmpeg
ffmpeg -i input.mp4 -c:v libx264 -crf 28 output.mp4
ffmpeg -i input.mp4 -vn -acodec libmp3lame output.mp3
```

## Tips
- Always check the output file exists and has reasonable size
- Use `head -5` to preview the first few lines
- Save outputs to ~/downloads/ for easy access
