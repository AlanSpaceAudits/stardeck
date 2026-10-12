#!/usr/bin/env python3
"""Trim the OpenNGC CSV files into the tab-separated catalogue the apps embed.

usage: make_catalog.py NGC.csv addendum.csv > catalog.tsv
columns: name, other names (| separated), type, RA J2000 hours, Dec J2000 degrees, V magnitude
Source: OpenNGC by Mattia Verga, CC-BY-SA-4.0, https://github.com/mattiaverga/OpenNGC
"""
import csv, re, sys


def sexagesimal(text):
    sign = -1 if text.startswith("-") else 1
    a, b, c = (float(x) for x in text.lstrip("+-").split(":"))
    return sign * (a + b / 60 + c / 3600)


def pretty(name):
    m = re.fullmatch(r"(NGC|IC)0*(\d+)(.*)", name)
    return f"{m[1]} {m[2]}{m[3]}" if m else name


for path in sys.argv[1:]:
    with open(path, newline="") as f:
        for row in csv.DictReader(f, delimiter=";"):
            if row["Type"] in ("Dup", "NonEx") or not row["RA"] or not row["Dec"]:
                continue
            other = [f"M {int(row['M'])}"] if row["M"] else []
            other += [n for n in row["Common names"].split(",") if n]
            print(pretty(row["Name"]), "|".join(other), row["Type"],
                  f"{sexagesimal(row['RA']):.5f}", f"{sexagesimal(row['Dec']):.4f}",
                  row["V-Mag"] or row["B-Mag"], sep="\t")
