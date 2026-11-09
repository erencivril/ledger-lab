#!/usr/bin/env python3
"""Prints a Markdown summary of a PIT mutations.xml (used for the GitHub step summary)."""
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

path = sys.argv[1] if len(sys.argv) > 1 else "build/reports/pitest/mutations.xml"
mutations = ET.parse(path).getroot().findall("mutation")

per_class = defaultdict(lambda: defaultdict(int))
survivors = []
for m in mutations:
    cls = m.findtext("mutatedClass").rsplit(".", 1)[-1]
    status = m.get("status")
    per_class[cls][status] += 1
    if status in ("SURVIVED", "NO_COVERAGE"):
        survivors.append((cls, m.findtext("mutatedMethod"), m.findtext("lineNumber"), m.findtext("description")))

def killed(counts):
    return counts["KILLED"] + counts["TIMED_OUT"] + counts["MEMORY_ERROR"] + counts["RUN_ERROR"]

total = len(mutations)
total_killed = sum(killed(c) for c in per_class.values())
score = 100 * total_killed / total if total else 0.0

print("## Mutation testing (PIT, domain package)\n")
print(f"**Mutation score: {score:.1f}%** ({total_killed} of {total} mutants killed)\n")
print("| Class | Mutants | Killed | Survived | No coverage |")
print("|---|---:|---:|---:|---:|")
for cls in sorted(per_class):
    c = per_class[cls]
    print(f"| {cls} | {sum(c.values())} | {killed(c)} | {c['SURVIVED']} | {c['NO_COVERAGE']} |")
if survivors:
    print("\n### Surviving mutants\n")
    for cls, method, line, desc in survivors:
        print(f"- `{cls}.{method}` line {line}: {desc}")
else:
    print("\nNo surviving mutants.")
