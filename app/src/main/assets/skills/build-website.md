# Skill: Build a Website

## When to Use
The user wants to build a website, web app, or web page. This includes:
- Static websites (HTML/CSS/JS)
- Dynamic web apps (Python Flask, Node Express)
- Single-page apps (React, Vue, Svelte)

## Prerequisites
- Linux environment installed (Settings → Linux Environment)
- If not installed: tell user to install it first

## Steps

### 1. Install Dependencies
```bash
# For static sites: no dependencies needed
# For Node.js apps:
apk add nodejs npm

# For Python web apps:
apk add python3 py3-pip
pip install flask

# For serving static files:
apk add python3  # use: python3 -m http.server 8080
```

### 2. Create the Project
```bash
mkdir -p ~/projects/website
cd ~/projects/website
```

### 3. Build the Site
- For static: create index.html, style.css, script.js
- For Flask: create app.py with routes
- For Node: create server.js or use a framework

### 4. Serve Locally (for testing)
```bash
# Static files:
cd ~/projects/website && python3 -m http.server 8080 &

# Flask:
FLASK_APP=app.py flask run --host=0.0.0.0 --port=8080 &

# Node:
node server.js &
```

### 5. Share with User
The user can access the site at `http://localhost:8080` in their phone's browser.
To make it accessible from other devices on the same network, use `--host=0.0.0.0`.

### 6. Export
Copy the final files to `~/downloads/` so the user can download them via the Files browser.

## Tips
- Use `--no-cache` with apk to save disk space
- For production: minify CSS/JS, optimize images
- Keep the workspace organized: `~/projects/website/` for code, `~/downloads/` for output
