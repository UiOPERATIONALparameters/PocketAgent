# Skill: Make a Chart

## When to Use
The user wants to create a chart, graph, or visualization.

## Prerequisites
- Linux environment installed
- Python + matplotlib needed

## Steps

### 1. Install matplotlib
```bash
pkg install python
pip install matplotlib
```

### 2. Write the Chart Script
```python
#!/usr/bin/env python3
import matplotlib
matplotlib.use('Agg')  # non-interactive backend
import matplotlib.pyplot as plt

plt.figure(figsize=(10, 6))
plt.plot([1,2,3,4,5], [2,4,6,8,10], 'b-o', label='Linear')
plt.xlabel('X Axis')
plt.ylabel('Y Axis')
plt.title('My Chart')
plt.legend()
plt.grid(True)
plt.savefig('downloads/chart.png', dpi=150, bbox_inches='tight')
```

### 3. Run the Script
```bash
python ~/projects/chart.py
```

## Tips
- Always use `matplotlib.use('Agg')` — no display available
- Save to `~/downloads/` for easy access
