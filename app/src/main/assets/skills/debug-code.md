# Skill: Debug Code

## When to Use
The user has code that isn't working.

## Steps

### 1. Read the Error Message
Parse: file/line number, error type, stack trace.

### 2. Reproduce the Issue
```bash
python ~/projects/broken.py 2>&1 | tee /tmp/error.log
echo $?
```

### 3. Common Error Patterns
- `ModuleNotFoundError`: `pip install <module>` or `pkg install python-<module>`
- `FileNotFoundError`: check path, use `ls`
- `PermissionError`: `chmod +r` or `chmod +x`
- `command not found`: `pkg install <package>`

### 4. Add Debugging Output
```python
print(f"DEBUG: variable = {variable}")
import traceback; traceback.print_exc()
```

### 5. Fix and Verify
- Make the fix using `str_replace`
- Re-run the code
- Clean up debug statements

## Tips
- Don't guess — read the error message first
- When stuck, search the web: `web_search("error message")`
