"""Verify file hashes from an extracted distribution, using Python standard library."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parent
manifest=json.loads((root/'MANIFEST.json').read_text(encoding='utf-8'))
failures=[]
for rel,wanted in manifest['sha256'].items():
    file=(root/rel).resolve()
    if not file.is_relative_to(root) or not file.is_file():failures.append(rel+': missing/invalid');continue
    if hashlib.sha256(file.read_bytes()).hexdigest()!=wanted:failures.append(rel+': hash mismatch')
if failures:raise SystemExit('\n'.join(failures))
print(f"PACKAGE_OK {manifest['package']} {manifest['version']} files={len(manifest['sha256'])}")
