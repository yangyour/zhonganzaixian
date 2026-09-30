"""Check local development documents and generate traceability and source manifests.

Run using the bundled Python. This checks design files, not product behavior.
Pass --node and --packages to additionally render the offline reading edition.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

DOC = Path(__file__).resolve().parents[1]
PROJECT = DOC.parents[1]
DATE = "2026-09-30"


def read_json(p):
    return json.loads(p.read_text(encoding="utf-8-sig"))


def write_json(p, data):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--node")
    parser.add_argument("--packages")
    args = parser.parse_args()
    requirements = read_json(DOC / "evidence/contract_requirements.json")
    config = read_json(DOC / "contracts/config-baseline.json")
    api = read_json(DOC / "contracts/core-api.openapi.json")
    rows = requirements["requirements"]
    errors, checks = [], []

    def check(name, ok, detail=""):
        checks.append({"check": name, "passed": bool(ok), "detail": detail})
        if not ok:
            errors.append(name + ": " + str(detail))

    ids = [r["id"] for r in rows]
    check("contract_unique_128", len(ids) == len(set(ids)) == 128)
    for src in requirements["sources"]:
        p = PROJECT / src["path"]
        check("source_hash:" + p.name, p.exists() and sha(p) == src["file_sha256"])

    qdoc = (DOC / "04_客户待确认事项与全局设置.md").read_text(encoding="utf-8")
    qids = set(re.findall(r"^### (Q\d{2})\b", qdoc, re.M))
    check("question_ids_31", qids == {f"Q{i:02}" for i in range(1, 32)})
    check("blank_customer_decisions", len(re.findall(r"客户结论：[＿_]+", qdoc)) == 31)
    keys = [s["key"] for s in config["settings"]]
    check("unique_setting_keys", len(keys) == len(set(keys)), str(len(keys)))
    for item in config["settings"]:
        if item["decision_status"] == "pending":
            check("pending_null:" + item["key"], item["value"] is None and bool(item["question_ids"]))
        else:
            check("known_value:" + item["key"], item["value"] is not None)
        check("setting_questions:" + item["key"], set(item["question_ids"]) <= qids)
    check("business_timezone", config["timezone"] == "Asia/Shanghai")
    module_doc = (DOC / "02_模块开发步骤与页面交互.md").read_text(encoding="utf-8")
    html_files = sorted((PROJECT / "zabbix-msp-prototype").glob("*.html"))
    check("prototype_pages_31", len(html_files) == 31)
    for p in html_files:
        check("page_covered:" + p.name, p.name in module_doc)

    module_task = {"M1": "D02/D08", "M2": "D03/D04/D05", "M3": "D05", "M4": "D06/D13", "M5": "D07/D08", "M6": "D10", "M7": "D11", "M8": "D09", "M9": "D12", "M10": "D08/D12/D13"}
    module_q = {"M1": "Q05/Q06/Q25", "M2": "Q02/Q03/Q09/Q16/Q17", "M3": "Q19/Q20/Q28/Q31", "M4": "Q03/Q06/Q14/Q19/Q20/Q29", "M5": "Q07/Q08/Q09/Q14/Q15/Q26/Q27", "M6": "Q10/Q20/Q22/Q28", "M7": "Q01/Q03/Q09/Q10/Q28", "M8": "Q14/Q17/Q18/Q24/Q28", "M9": "Q05/Q11/Q23/Q24/Q26", "M10": "Q04/Q05/Q13/Q23/Q27/Q30"}
    module_title = {"M1": "首页门户", "M2": "租户与权限", "M3": "设备台账", "M4": "监控与告警", "M5": "工单与SLA", "M6": "配置备份", "M7": "远程处置与审计", "M8": "客户门户与通知", "M9": "月报与体检报告", "M10": "经营数据看板"}
    trace = ["# MOP平台合同逐项开发追踪", "", "版本V0.1，2026年9月30日。下表逐行来自实际合同DOCX的功能表，共128项；它连接合同、开发步骤和待执行验收，不表示实现或验收通过。", "", "每项功能按以下顺序执行：读取合同原文与验收 → 按02对应模块步骤建模及接口 → 实现页面和领域逻辑 → 接入已批准适配器 → 执行正向/权限/边界/异常用例 → 关联证据后更新状态。01的D编号规定跨模块先后顺序。", "", "模块关联Q仅为查阅索引，不代表每项需求都受该模块全部Q阻塞；具体暂停动作以04各Q的‘必须暂停’和02冻结点为准。合同固定部分可以先实现。所有需求优先级、负责人、实际结果及证据暂留空，客户未确认的可选项不自行删去。", "", "原文及表格定位见 [合同原文数据](evidence/contract_requirements.json)，具体步骤见 [模块开发](02_模块开发步骤与页面交互.md)，通用执行见 [开发顺序](01_开发顺序与任务拆解.md)，验证流程见 [测试验收](06_测试验收与交付步骤.md)。", ""]
    for module in module_title:
        trace += [f"## {module} {module_title[module]}", "", f"实施工作项：{module_task[module]}；模块确认索引：{module_q[module]}。", "", "|需求编号|合同名称|合同标记|原文位置|验收入口|需求优先级|负责人|实际结果与证据|", "|---|---|---|---|---|---|---|---|"]
        for r in rows:
            if r["id"].split("-")[0] != module:
                continue
            title = r["title"].replace("|", "／").replace("\n", " ")
            trace.append(f"|{r['id']}|{title}|{r['scope']}|{r['source']['locator']}|TC-{r['id']}| | | |")
        trace.append("")
    trace += ["## 验收原文逐项摘录", "", "以下验收要求从合同功能表提取，空缺表示该行未单列‘验收’文字，仍须满足整行功能及附件四；不据此免除测试。每个入口执行后补输入、步骤、预期、实际、证据、缺陷和执行人。", ""]
    for r in rows:
        trace += [f"### TC-{r['id']} {r['title']}", "", f"来源：{r['source']['locator']}；范围：{r['scope']}。", "", r["acceptance_text"] or "该行未单列验收句，请按合同原文功能要求及附件四细化用例。", "", "执行状态：NOT_RUN；需求优先级：＿＿＿＿；执行人：＿＿＿＿；日期：＿＿＿＿；实际结果与证据：＿＿＿＿。", ""]
    (DOC / "07_合同逐项开发追踪.md").write_text("\n".join(trace), encoding="utf-8")

    operation_ids = []
    check("openapi_version", api["openapi"] == "3.1.0")
    for path, methods in api["paths"].items():
        for method, op in methods.items():
            operation_ids.append(op["operationId"])
            path_params = set(re.findall(r"\{([^}]+)\}", path))
            declared = {p["name"] for p in op.get("parameters", []) if p["in"] == "path" and p.get("required")}
            check("path_parameters:" + op["operationId"], path_params == declared)
            if method == "post":
                headers = {p["name"] for p in op["parameters"] if p["in"] == "header" and p.get("required")}
                check("write_headers:" + op["operationId"], {"Idempotency-Key", "X-CSRF-Token"} <= headers)
    check("unique_operation_ids", len(operation_ids) == len(set(operation_ids)))

    def walk_refs(node):
        if isinstance(node, dict):
            if "$ref" in node:
                value = api
                try:
                    for part in node["$ref"].removeprefix("#/").split("/"):
                        value = value[part]
                    check("ref:" + node["$ref"], True)
                except (KeyError, TypeError):
                    check("ref:" + node["$ref"], False)
            for value in node.values():
                walk_refs(value)
        elif isinstance(node, list):
            for value in node:
                walk_refs(value)
    walk_refs(api)
    check("tenant_three_modes", set(api["components"]["schemas"]["TenantCreate"]["properties"]["isolation_mode"]["enum"]) == {"shared", "dedicated", "independent"})
    check("partial_area_not_department", "partial_area" in api["components"]["schemas"]["TicketCreate"]["properties"]["impact_scope"]["enum"])

    md_files = sorted(DOC.glob("*.md"))
    for md in md_files:
        text = md.read_text(encoding="utf-8")
        for target in re.findall(r"\]\(([^)]+)\)", text):
            if target.startswith(("http:", "https:", "#", "mailto:")):
                continue
            target = target.split("#")[0].strip("<>")
            check("link:" + md.name + ":" + target, (md.parent / target).exists())
        used = set(re.findall(r"\bQ\d{2}\b", text))
        check("valid_question_refs:" + md.name, used <= qids, str(sorted(used - qids)))
        check("consistent_config_error:" + md.name, not re.search(r"CONFIG_(?:UNCONFIRMED|AWAITING_CONFIRMATION)", text))

    source_paths = [PROJECT / s["path"] for s in requirements["sources"]]
    source_paths += html_files
    source_paths += sorted((PROJECT / "zabbix-msp-prototype/assets/js").glob("*.js"))
    source_paths += sorted((PROJECT / "zabbix-msp-prototype").glob("*.md"))
    source_paths += [PROJECT / "tmp/project_docs/formal/design_content.md", PROJECT / "tmp/project_docs/formal/plan_content.md"]
    source_paths += sorted((PROJECT / "output/documents").glob("*客户开发资料*.docx"))
    manifest = [{"path": p.relative_to(PROJECT).as_posix(), "bytes": p.stat().st_size, "sha256": sha(p)} for p in source_paths]
    write_json(DOC / "evidence/source_manifest.json", {"date": DATE, "purpose": "开发设计依据校验值；不包含生产账号或秘密", "sources": manifest})
    inventory_path = DOC / "evidence/prototype_inventory.json"
    check("inventory_exists", inventory_path.exists())
    if inventory_path.exists():
        inv = read_json(inventory_path)
        check("inventory_count", inv.get("page_count") == 31)
    summary = {"date": DATE, "scope": "文档静态一致性与设计契约结构检查；非产品测试/非完整OpenAPI规范验证器", "requirements": len(ids), "prototype_pages": len(html_files), "questions": len(qids), "settings": len(keys), "pending_settings": sum(s["decision_status"] == "pending" for s in config["settings"]), "api_operations": len(operation_ids), "checks": len(checks), "passed": not errors, "errors": errors}
    write_json(DOC / "evidence/document_validation.json", {**summary, "details": checks})
    print(json.dumps(summary, ensure_ascii=False))
    if errors:
        raise SystemExit(1)
    if args.node:
        if not args.packages:
            parser.error("--packages is required with --node")
        subprocess.run([args.node, str(DOC / "tools/render_docs.mjs"), args.packages], check=True)


if __name__ == "__main__":
    main()
