# Skill: Build a Website

## When to Use
The user wants to build a website, web app, or web page.

## Prerequisites
- Linux environment installed (Settings → Linux Environment)
- If not installed: tell user to install it first

## Steps

### 1. Install Dependencies
```bash
# For static sites: no dependencies needed
# For Node.js apps:
pkg install nodejs

# For Python web apps:
pkg install python
pip install flask

# For serving static files:
pkg install python
# use: python -m http.server 8080
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
cd ~/projects/website && python -m http.server 8080 &

# Flask:
FLASK_APP=app.py flask run --host=0.0.0.0 --port=8080 &

# Node:
node server.js &
```

### 5. Share with User
The user can access the site at `http://localhost:8080` in their phone's browser.

### 6. Export
Copy the final files to `~/downloads/` so the user can download them via the Files browser.

## Tips
- Use `pkg install -y` for non-interactive installs
- Keep the workspace organized: `~/projects/website/` for code, `~/downloads/` for output
