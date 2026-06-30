# Skill: Make a Chart

## When to Use
The user wants to create a chart, graph, or visualization. This includes:
- Bar charts, line charts, pie charts
- Scatter plots, histograms
- Data visualization from CSV/JSON

## Prerequisites
- Linux environment installed
- Python3 + matplotlib needed

## Steps

### 1. Install matplotlib
```bash
apk add python3 py3-pip
pip install matplotlib
```

### 2. Write the Chart Script
Use `file_write` to create a Python script:
```python
#!/usr/bin/env python3
import matplotlib
matplotlib.use('Agg')  # non-interactive backend
import matplotlib.pyplot as plt

# Data
x = [1, 2, 3, 4, 5]
y = [2, 4, 6, 8, 10]

# Create chart
plt.figure(figsize=(10, 6))
plt.plot(x, y, 'b-o', label='Linear')
plt.xlabel('X Axis')
plt.ylabel('Y Axis')
plt.title('My Chart')
plt.legend()
plt.grid(True)

# Save to file
plt.savefig('downloads/chart.png', dpi=150, bbox_inches='tight')
print("Chart saved to downloads/chart.png")
```

### 3. Run the Script
```bash
python3 ~/projects/chart.py
```

### 4. Share with User
The chart is saved to `~/downloads/chart.png`. The user can:
- View it in the Files browser
- Save it to their phone's Downloads folder (tap the download icon)

## Chart Types

### Bar Chart
```python
categories = ['A', 'B', 'C', 'D']
values = [23, 45, 12, 67]
plt.bar(categories, values)
```

### Pie Chart
```python
labels = ['Apples', 'Oranges', 'Bananas']
sizes = [30, 45, 25]
plt.pie(sizes, labels=labels, autopct='%1.1f%%')
```

### Scatter Plot
```python
plt.scatter(x, y, c='red', alpha=0.5)
```

### Histogram
```python
plt.hist(data, bins=20)
```

## Tips
- Always use `matplotlib.use('Agg')` — no display available in proot
- Save to `~/downloads/` for easy access
- Use `dpi=150` for good quality without huge files
- Use `bbox_inches='tight'` to avoid clipping
- For data from CSV: `pip install pandas` then `df = pd.read_csv('data.csv')`
