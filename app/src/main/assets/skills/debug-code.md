# Skill: Debug Code

## When to Use
The user has code that isn't working. This includes:
- Error messages
- Unexpected behavior
- Crashes
- Performance issues

## Steps

### 1. Read the Error Message
Error messages are your best friend. Parse them carefully:
- **File and line number**: tells you WHERE the error is
- **Error type**: tells you WHAT went wrong
- **Stack trace**: tells you HOW you got there

### 2. Reproduce the Issue
```bash
# Run the code and capture output:
python3 ~/projects/broken.py 2>&1 | tee /tmp/error.log

# Check exit code:
echo $?
```

### 3. Common Error Patterns

#### Python
- `ModuleNotFoundError`: `pip install <module>`
- `FileNotFoundError`: check path, use `ls` to verify
- `PermissionError`: `chmod +r <file>` or `chmod +x <file>`
- `SyntaxError`: check for missing colons, parentheses, quotes
- `IndentationError`: check for mixed tabs/spaces
- `KeyError`/`IndexError`: check data structure before accessing

#### Bash
- `command not found`: `apk add <package>`
- `permission denied`: `chmod +x <file>`
- `No such file or directory`: check path with `ls`
- `syntax error near unexpected token`: check quoting

#### Node.js
- `Cannot find module`: `npm install <module>`
- `EADDRINUSE`: port already in use, use a different port
- `EACCES`: permission denied, check file permissions

### 4. Add Debugging Output
```python
# Python:
print(f"DEBUG: variable = {variable}")
print(f"DEBUG: type = {type(variable)}")
import traceback; traceback.print_exc()
```

```bash
# Bash:
set -x  # print commands before executing
echo "DEBUG: var=$var" >&2
```

### 5. Isolate the Problem
- Comment out code to find the minimal failing case
- Add print statements before and after suspicious lines
- Test with simple input first

### 6. Fix and Verify
- Make the fix using `str_replace`
- Re-run the code
- Check the output matches expectations
- Clean up debug statements

## Tips
- Don't guess — read the error message first
- Use `grep -n "error" /tmp/error.log` to find error lines
- Check if the issue is environment-related (missing package, wrong path)
- For proot issues: check if the command works in `/system/bin/sh` vs proot
- Use `head -50` and `tail -50` to inspect large outputs
- When stuck, search the web: `web_search("error message")`
