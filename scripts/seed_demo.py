#!/usr/bin/env python3
"""Seed a demo workspace against a running GSB server (stdlib only)."""
import json, sys, urllib.request, urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:5223"
WS = sys.argv[2] if len(sys.argv) > 2 else "demo"
A = "a" * 64

def call(method, path, obj=None):
    data = json.dumps(obj).encode() if obj is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, body

def geom(w=8, h=6):
    return {"width": w, "height": h, "spacingX": 0.5, "spacingY": 0.5,
            "orientation": "L|A", "originX": 0, "originY": 0}

def vocab(v):
    return {"vocabVersion": v, "entries": [
        {"classId": 1, "name": "liver", "displayColor": "#cc0000"},
        {"classId": 2, "name": "tumor", "displayColor": "#00cc00"}]}

def labels(seed):
    out = []
    for i in range(48):
        x = i % 8
        if seed == "A":
            out.append(1 if x < 4 else (2 if i % 9 == 0 else 0))
        else:
            out.append(1 if x < 4 else (2 if i % 6 == 0 else 0))
    return out

s, r = call("POST", "/api/workspaces", {
    "workspaceId": WS, "name": "Demo 双来源",
    "image": {"imageId": "img", "fingerprintSha256": A},
    "canonicalGeometry": geom()})
print("workspace", s, r if s >= 400 else WS)

s, r = call("POST", f"/api/workspaces/{WS}/sources", {
    "algorithm": "algo-a", "image": {"imageId": "img", "fingerprintSha256": A},
    "geometry": geom(), "vocabulary": vocab("vocab-1"),
    "width": 8, "height": 6, "labels": labels("A"), "allowResample": False})
print("source A", s)
s, r = call("POST", f"/api/workspaces/{WS}/sources", {
    "algorithm": "algo-b", "image": {"imageId": "img", "fingerprintSha256": A},
    "geometry": geom(), "vocabulary": vocab("vocab-2"),
    "width": 8, "height": 6, "labels": labels("B"), "allowResample": False})
print("source B", s)
print(f"open {BASE}/ and choose workspace '{WS}'")
