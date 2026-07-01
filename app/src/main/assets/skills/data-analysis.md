# Skill: Data Analysis

## When to Use
The user wants to analyze data — statistics, trends, aggregations, or visualizations.

## Prerequisites
- Linux environment installed
- Python + pandas/matplotlib

## Steps

### 1. Install Dependencies
```bash
pkg install python
pip install pandas matplotlib numpy
```

### 2. Load the Data
```python
import pandas as pd

# CSV
df = pd.read_csv('data.csv')

# JSON
df = pd.read_json('data.json')

# Excel (needs openpyxl)
pip install openpyxl
df = pd.read_excel('data.xlsx')
```

### 3. Explore the Data
```python
print(df.shape)        # rows, columns
print(df.columns)      # column names
print(df.head())       # first 5 rows
print(df.describe())   # statistics
print(df.info())       # data types
```

### 4. Analyze
```python
# Group by
summary = df.groupby('category')['value'].sum()

# Filter
filtered = df[df['value'] > 100]

# Sort
sorted_df = df.sort_values('value', ascending=False)

# Correlation
corr = df.corr()
```

### 5. Visualize
```python
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

df.plot(kind='bar', x='category', y='value')
plt.savefig('downloads/chart.png', dpi=150, bbox_inches='tight')
```

### 6. Save Results
```python
df.to_csv('downloads/results.csv', index=False)
```

## Tips
- Always check data types before analysis
- Handle missing values: `df.dropna()` or `df.fillna(0)`
- Use `df.head()` to preview before processing
- Save visualizations to ~/downloads/
