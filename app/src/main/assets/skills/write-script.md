# Skill: Write a Script

## When to Use
The user wants to write a script to automate a task.

## Prerequisites
- Linux environment installed (for Python/Node)

## Steps

### 1. Install Runtime
```bash
pkg install python  # or: pkg install nodejs
```

### 2. Write the Script
Use `file_write` to create the script.

### 3. Run and Test
```bash
python ~/projects/myscript.py
# or
node ~/projects/myscript.js
```

### 4. Install Dependencies
```bash
pip install requests beautifulsoup4  # Python
npm install axios cheerio             # Node
```

## Tips
- Start simple, add complexity incrementally
- Test after each change
- Save scripts in `~/projects/`
