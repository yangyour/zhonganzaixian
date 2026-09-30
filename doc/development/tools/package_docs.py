"""Package authored docs and offline reader, preserving working relative links."""
from pathlib import Path
import hashlib
import json
import zipfile

doc = Path(__file__).resolve().parents[1]
project = doc.parents[1]
reader = project / "output/documents/MOP平台开发文档.html"
validation = json.loads((doc / "evidence/document_validation.json").read_text(encoding="utf-8"))
if not validation["passed"]:
    raise SystemExit("Document validation has not passed.")
files = sorted(p for p in doc.rglob("*") if p.is_file() and "__pycache__" not in p.parts)
files.append(reader)
archive = project / "output/development/MOP平台开发文档_V0.1.zip"
archive.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
    for p in files:
        bundle.write(p, p.relative_to(project).as_posix())
with zipfile.ZipFile(archive) as bundle:
    assert bundle.testzip() is None
    for p in files:
        assert bundle.read(p.relative_to(project).as_posix()) == p.read_bytes()
print(json.dumps({"archive": str(archive), "files": len(files), "bytes": archive.stat().st_size, "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "verified": True}, ensure_ascii=False))
