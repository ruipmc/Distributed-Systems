#!/usr/bin/env python3
import csv
import sys
from collections import defaultdict
import statistics as stats

try:
    import matplotlib.pyplot as plt
except Exception as e:
    print("matplotlib is required: pip install matplotlib")
    sys.exit(1)

SUMMARY_FILE = "convergence_summary.csv"

# Read data
by_n = defaultdict(list)
with open(SUMMARY_FILE, newline="") as f:
    reader = csv.DictReader(f)
    for row in reader:
        n = int(row["n"])
        time_seconds = float(row["time_seconds"])  # negative -> timeout
        # Only include successful runs for average plot
        if time_seconds >= 0:
            by_n[n].append(time_seconds)

# Prepare series
Ns = sorted(by_n.keys())
if not Ns:
    print("No data found in convergence_summary.csv. Run ExtraMarks first.")
    sys.exit(1)

avg_times = [sum(by_n[n]) / len(by_n[n]) for n in Ns]
min_times = [min(by_n[n]) for n in Ns]
max_times = [max(by_n[n]) for n in Ns]
std_times = [stats.pstdev(by_n[n]) if len(by_n[n]) > 1 else 0.0 for n in Ns]

# Plot
plt.figure(figsize=(7, 4.5))
plt.plot(Ns, avg_times, marker='o', label='Average convergence time')
plt.fill_between(Ns, min_times, max_times, color='gray', alpha=0.2, label='Min–Max range')
plt.errorbar(Ns, avg_times, yerr=std_times, fmt='none', ecolor='red', alpha=0.6, label='Std dev')
plt.title('Convergence time towards 1/N vs N')
plt.xlabel('Number of peers (N)')
plt.ylabel('Time to converge (s)')
plt.grid(True, linestyle='--', alpha=0.4)
plt.legend()
plt.tight_layout()
plt.savefig('convergence_time.png', dpi=160)
print('Saved plot to convergence_time.png')
