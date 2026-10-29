#!/usr/bin/env python3
"""Reproduce the failed new oracle's division-rounding discrepancy."""
from decimal import Decimal, localcontext
import gzip
from pathlib import Path

rows = gzip.decompress(Path(__file__).with_name("wide-curve-oracle-geometry.txt.gz").read_bytes()).decode().splitlines()
def point(text):
    return tuple(Decimal.from_float(float(v)) for v in text.split(","))
x, y = point(rows[0])
exact = divided = relative = 0
with localcontext() as context:
    context.prec = 80
    for row in rows[1:]:
        points = [point(v) for v in row.split(";")]
        for (ax, ay), (bx, by) in zip(points, points[1:] + points[:1]):
            if (ay <= y < by) or (by <= y < ay):
                orientation = (bx-ax)*(y-ay)-(by-ay)*(x-ax)
                sign = 1 if by > ay else -1
                if (orientation > 0 if sign == 1 else orientation < 0):
                    exact += sign
                a, b = (float(ax), float(ay)), (float(bx), float(by))
                px, py = float(x), float(y)
                if a[0]+(py-a[1])*(b[0]-a[0])/(b[1]-a[1]) > px:
                    divided += sign
                cross = (b[0]-a[0])*(py-a[1])-(b[1]-a[1])*(px-a[0])
                if (cross > 0 if sign == 1 else cross < 0):
                    relative += sign
assert (exact, divided, relative) == (1, 0, 1)
print(f"Exact Double-input decimal winding={exact}; division={divided}; relative={relative}")
