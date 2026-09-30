#!/usr/bin/env python3
"""Validate a resolved audit inventory, or recover its single JSON record from Actions logs."""
import argparse
import json
from pathlib import Path
import re

SCOPES = {":app:debugRuntimeClasspath", ":app:releaseRuntimeClasspath",
          ":app:debugUnitTestRuntimeClasspath", ":app:releaseUnitTestRuntimeClasspath",
          ":build:classpath"}
MARKER = "MUON_DEPENDENCY_INVENTORY "


def parse(text, commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected full commit SHA")
    text = text.strip()
    if not text.startswith("{"):
        records = [line.split(MARKER, 1)[1] for line in text.splitlines() if MARKER in line]
        if len(records) != 1:
            raise ValueError("Expected one complete inventory record in logs")
        text = records[0]
    data = json.loads(text)
    if not isinstance(data, dict) or type(data.get("schema")) is not int or data.get("schema") != 1 or data.get("commit") != commit:
        raise ValueError("Inventory schema/commit mismatch")
    scopes = data.get("configurations")
    if not isinstance(scopes, list) or len(scopes) != len(SCOPES):
        raise ValueError("Incomplete inventory scopes")
    seen = set()
    for scope in scopes:
        if not isinstance(scope, dict):
            raise ValueError("Invalid inventory scope")
        name = scope.get("scope")
        if name not in SCOPES or name in seen:
            raise ValueError("Unexpected or duplicate scope")
        seen.add(name)
        modules = scope.get("modules")
        if not isinstance(modules, list) or not modules:
            raise ValueError("Empty component inventory")
        if any(not isinstance(m, str) or len(m.split(":")) != 3 or not all(m.split(":"))
               or any(c.isspace() for c in m) for m in modules):
            raise ValueError("Invalid Maven component coordinate")
        if modules != sorted(set(modules)):
            raise ValueError("Inventory components must be unique and sorted")
        if "edges" in scope:
            edges = scope["edges"]
            if not isinstance(edges, list) or not edges:
                raise ValueError("Empty dependency parent edges")
            records = []
            for edge in edges:
                if not isinstance(edge, dict) or set(edge) != {"from", "to", "constraint"}:
                    raise ValueError("Invalid dependency edge")
                parent, child = edge["from"], edge["to"]
                if not isinstance(parent, str) or not isinstance(child, str) or type(edge["constraint"]) is not bool:
                    raise ValueError("Invalid dependency edge values")
                if parent != "<root>" and parent not in modules or child not in modules:
                    raise ValueError("Unknown dependency edge component")
                records.append((parent, child, edge["constraint"]))
            if records != sorted(set(records)):
                raise ValueError("Dependency edges must be unique and sorted")
    return data


if __name__ == "__main__":
    args = argparse.ArgumentParser()
    args.add_argument("file", type=Path)
    args.add_argument("--commit", required=True)
    options = args.parse_args()
    inventory = parse(options.file.read_text(encoding="utf-8-sig"), options.commit)
    print(json.dumps(inventory, sort_keys=True))
