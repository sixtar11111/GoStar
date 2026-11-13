"""Create a fresh public source folder using a fixed list. Never copy private repo trees."""
from __future__ import annotations
import argparse, hashlib, json, re, shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FILES=(
 'CodexAssistant.java','README.md','README.zh-CN.md','RELEASE_NOTES.md','.gitignore',
 'plugin/build_plugin.py','plugin/README.md',
 'scripts/starccm_skill_bridge.py','scripts/gostar_local_help.py',
 'packaging/build_distributions.py','packaging/verify_package.py','packaging/prepare_public_source.py',
 'docs/INSTALL.md','docs/REPORTS.md','docs/VALIDATION_061.md','docs/VALIDATION_062.md','docs/SECURITY_REVIEW_062.md',
 'tests/AerodynamicReports061Native.java','tests/AerodynamicReports061Readback.java',
 'tests/Reports061ControllerRegression.java','tests/test_gostar_release.py',
 'tests/Controller059Regression.java','tests/Api060ControllerRegression.java','tests/ChangeSummary060Smoke.java',
 'tests/ApiDocumentation060Test.java','tests/NativeApiBridgePolicy060Smoke.java','tests/CommonActions060Smoke.java',
 'tests/Security062Regression.java','tests/run_security062.sh',
 'tests/run_reports061_native.sh','tests/run_controllers_061.sh','tests/run_controller_059.sh','tests/run_api_policy_060.sh',
 'dist/GoStar-0.6.2.zip','dist/GoStar-0.6.2.nbm','dist/SHA256SUMS.txt',
)
TREES={'plugin/src':{'.java'},'plugin/resources':{'.xml','.properties','.png'},'runtime/knowledge':{'.md'}}
SECRET=re.compile(rb'(?:sk-[A-Za-z0-9][A-Za-z0-9._-]{24,}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----)')

def prepare(destination:Path):
 if destination.exists() and any(destination.iterdir()):raise SystemExit('Refusing nonempty destination: '+str(destination))
 destination.mkdir(parents=True,exist_ok=True)
 selected=[ROOT/p for p in FILES]
 for tree,suffixes in TREES.items():
  selected += [p for p in sorted((ROOT/tree).rglob('*')) if p.is_file() and p.suffix in suffixes]
 for p in selected:
  if not p.is_file() or p.is_symlink():raise SystemExit('Missing/unsafe allowlisted input: '+str(p))
  if p.suffix!='.zip' and SECRET.search(p.read_bytes()):raise SystemExit('Possible credential in allowlisted file (contents not printed): '+str(p.relative_to(ROOT)))
  target=destination/p.relative_to(ROOT);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,target)
  if target.suffix=='.sh':target.chmod(0o755)
 files=sorted(p for p in destination.rglob('*') if p.is_file())
 record={'project':'GoStar','version':'0.6.2','publication_state':'PREPARED','remote':'https://github.com/sixtar11111/GoStar.git','vendor_binaries':False,'vendor_help_corpus':False,'user_simulations':False,'credentials':False,'sha256':{p.relative_to(destination).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
 (destination/'PUBLIC_MANIFEST.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 print(json.dumps({'destination':str(destination),'files':len(files)+1,'state':record['publication_state']}))
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('destination',type=Path);args=parser.parse_args();prepare(args.destination.resolve())
