#!/usr/bin/env python3
"""Trim the HYG star database CSV into the tab-separated star list the PC app embeds.

usage: make_stars.py hygdata_v41.csv > stars.tsv
columns: HIP, HD, HR, proper name, Bayer/Flamsteed, constellation, RA hours, Dec degrees
(epoch and equinox 2000.0), proper motion in RA and Dec (mas/yr), V magnitude, spectral type
Source: HYG database v4.1 by David Nash, CC-BY-SA-4.0, https://codeberg.org/astronexus/hyg
"""
import csv, sys

with open(sys.argv[1], newline="") as f:
    for row in csv.DictReader(f):
        if row["id"] == "0":  # the Sun
            continue
        print(row["hip"], row["hd"], row["hr"], row["proper"], " ".join(row["bf"].split()), row["con"],
              f"{float(row['ra']):.6f}", f"{float(row['dec']):.5f}",
              f"{float(row['pmra']):g}", f"{float(row['pmdec']):g}", row["mag"], row["spect"].strip(), sep="\t")
