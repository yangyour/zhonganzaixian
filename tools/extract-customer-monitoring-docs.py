"""Deterministic, read-only OOXML extraction. Document text is data, never code."""
import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

NS = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
NAMES = ("告警恢复规则与SLA工单流程口径规范.docx", "运维托管平台_监控项清单_V1.2(1).docx")


def extract(path):
    with ZipFile(path) as archive:
        body = ET.fromstring(archive.read("word/document.xml")).find("w:body", NS)
    blocks, tables = [], {}
    for index, child in enumerate(body, 1):
        ref = f"B{index}"
        if child.tag.endswith("}p"):
            text = "".join(node.text or "" for node in child.findall(".//w:t", NS))
            if text.strip():
                blocks.append({"ref": ref, "text": text})
        elif child.tag.endswith("}tbl"):
            rows = []
            for row_index, row in enumerate(child.findall("w:tr", NS), 1):
                cells = [
                    " / ".join("".join(n.text or "" for n in p.findall(".//w:t", NS))
                               for p in cell.findall("w:p", NS))
                    for cell in row.findall("w:tc", NS)
                ]
                rows.append(cells)
                blocks.append({"ref": f"{ref}.R{row_index}", "cells": cells})
            tables[ref] = rows
    return blocks, tables


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    manifest, outputs = [], {}
    for index, name in enumerate(NAMES, 1):
        path = args.directory / name
        blocks, tables = extract(path)
        sha = hashlib.sha256(path.read_bytes()).hexdigest()
        manifest.append({"name": name, "sha256": sha, "bytes": path.stat().st_size,
                         "extraction": f"document-{index}.json"})
        outputs[f"document-{index}.json"] = {"source_sha256": sha, "blocks": blocks}
        if index == 2:
            items = []
            for table_ref, rows in tables.items():
                if rows[0] != ["编号", "监控项", "采集方式  /  频率", "警告阈值", "严重阈值", "事件等级", "档位开放"]:
                    continue
                for row_index, row in enumerate(rows[1:], 2):
                    if len(row) != 7 or not re.fullmatch(r"[A-Z]{2}-\d{2}", row[0]):
                        continue
                    fields = ("rule_code", "name", "collection", "warning", "critical", "priority", "tier")
                    item = dict(zip(fields, row))
                    item.update(source_ref=f"{table_ref}.R{row_index}", group=row[0].split("-")[0],
                                requires_written_consent=row[0] in ("BA-04", "BA-05"),
                                deployment_status="NOT_ACTIVATED")
                    items.append(item)
            if len(items) != 234 or len({i["rule_code"] for i in items}) != 234:
                raise ValueError("Expected exactly 234 unique monitoring items")
            tiers = Counter(re.sub(r"\s+", "", item["tier"]) for item in items)
            actual = {"BASIC": tiers["全档"], "STANDARD": tiers["全档"] + tiers["标准+"],
                      "ADVANCED": len(items) - tiers["平台侧"], "PLATFORM": tiers["平台侧"]}
            outputs["monitoring-catalog-v1.2.json"] = {
                "source_sha256": sha, "scope": "CUSTOMER_DOCUMENT_BASELINE_NOT_DEPLOYED",
                "declared_tier_counts": {"BASIC": 12, "STANDARD": 132, "ADVANCED": 226, "PLATFORM": 8},
                "computed_tier_counts": actual, "group_counts": dict(sorted(Counter(i["group"] for i in items).items())),
                "items": items,
            }
    outputs["source-manifest.json"] = {"received_date": "2026-10-20", "sources": manifest}
    for name, value in outputs.items():
        content = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
        target = args.directory / name
        if args.check:
            if target.read_text(encoding="utf-8") != content:
                raise ValueError(f"Extraction drift: {name}")
        else:
            target.write_text(content, encoding="utf-8")
    print(json.dumps({"status": "VERIFIED" if args.check else "EXTRACTED",
                      "sources": len(manifest), "monitoring_items": len(items),
                      "computed_tier_counts": actual}, ensure_ascii=True))


if __name__ == "__main__":
    main()
