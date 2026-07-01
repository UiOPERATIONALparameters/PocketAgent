# Skill: File Management

## When to Use
The user wants to organize, search, or manage files in the workspace.

## Common Operations

### List Files
```
file_list(".")              # list root
file_list("projects", recursive=true)  # recursive list
glob("**/*.py")             # find all Python files
glob("downloads/*.pdf")     # find PDFs in downloads
```

### Search File Contents
```
grep("function", path="projects")     # search for "function"
grep("TODO", path=".", glob="*.py")  # search in Python files only
grep("error", case_insensitive=true) # case-insensitive search
```

### Read Files
```
file_read("config.json")                    # read entire file
file_read("large_file.log", start_line=100, end_line=200)  # read lines 100-200
```

### Write/Edit Files
```
file_write("new_file.txt", "content")           # create new file
str_replace("config.json", "old_value", "new_value")  # surgical edit
file_write("log.txt", "new entry", append=true)       # append to file
```

### Organize Files (via bash)
```bash
# Create directories
mkdir -p projects/web projects/api projects/docs

# Move files
mv *.py projects/api/
mv *.html projects/web/

# Copy
cp important.txt backups/important_backup.txt

# Delete
rm old_temp.tmp
rm -rf old_project/  # recursive delete

# Compress
tar -czf archive.tar.gz projects/
unzip archive.zip -d extracted/
```

### Check Disk Usage
```bash
du -sh *              # size of each item in current dir
du -sh ~/downloads/   # size of downloads
df -h .               # available disk space
```

## Tips
- Use file_list or glob to find files, NOT bash ls/find
- Use grep to search contents, NOT bash grep
- Use str_replace for edits, NOT file_write (unless creating new)
- Keep workspace organized: ~/projects/ for code, ~/downloads/ for output
- Regularly clean ~/tmp/ to save space
