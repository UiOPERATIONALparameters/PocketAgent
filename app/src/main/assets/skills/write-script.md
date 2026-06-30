# Skill: Write a Script

## When to Use
The user wants to write a script to automate a task. This includes:
- Python scripts
- Bash scripts
- Node.js scripts
- Shell one-liners

## Prerequisites
- Linux environment installed (for Python/Node)
- If not installed: tell user to install it first

## Steps

### 1. Choose the Language
- **Python**: best for data processing, APIs, automation
- **Bash**: best for file operations, system admin, quick tasks
- **Node.js**: best for web scraping, async I/O, JSON processing

### 2. Install Runtime (if needed)
```bash
# Python:
apk add python3 py3-pip

# Node.js:
apk add nodejs npm

# Bash: already available
```

### 3. Write the Script
Use `file_write` to create the script:
```
file_write("projects/myscript.py", "#!/usr/bin/env python3\n...")
```

Or use `str_replace` to edit an existing script.

### 4. Make Executable (for bash/python)
```bash
chmod +x ~/projects/myscript.py
```

### 5. Run and Test
```bash
# Python:
python3 ~/projects/myscript.py

# Node:
node ~/projects/myscript.js

# Bash:
bash ~/projects/myscript.sh
# or (if executable):
~/projects/myscript.sh
```

### 6. Debug if Needed
- Read error messages carefully
- Use `print()` or `console.log()` for debugging
- For Python: `python3 -c "import pdb; pdb.set_trace()"` for breakpoints
- Check exit codes: `echo $?`

### 7. Install Dependencies (if needed)
```bash
# Python:
pip install requests beautifulsoup4

# Node:
npm install axios cheerio
```

## Tips
- Start simple, add complexity incrementally
- Test after each change
- Use `head -10` to preview output
- Save scripts in `~/projects/` for organization
- Use meaningful variable names
- Add comments for complex logic
- Handle errors gracefully (try/except in Python, try/catch in Node)
