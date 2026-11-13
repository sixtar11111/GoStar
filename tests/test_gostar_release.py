"""Portable local-release checks; no licensed STAR process, credentials or network."""
import importlib.util, io, json, os, re, subprocess, sys, tempfile, unittest, zipfile
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('help_local',ROOT/'scripts/gostar_local_help.py')
help_local=importlib.util.module_from_spec(spec);spec.loader.exec_module(help_local)

class GoStarReleaseTests(unittest.TestCase):
 def test_installed_help_not_redistributed(self):
  with tempfile.TemporaryDirectory() as temp:
   root=Path(temp);index=root/'oxygen-webhelp/app/search/index/htmlFileInfoList.js';index.parent.mkdir(parents=True)
   index.write_text('var htmlFileInfoList = '+json.dumps(['GUID-TEST.html@@@Force Coefficient Report@@@Configure force coefficients and reference values.'])+';',encoding='utf-8')
   (root/'GUID-TEST.html').write_text('<html><script>do not expose this script</script><h1>Force Coefficient Report</h1><p>Use verified reference area.</p></html>',encoding='utf-8')
   with patch.dict(os.environ,{'GOSTAR_HELP_ROOT':str(root)}):
    result=help_local.lookup('创建阻力系数报告，参考面积如何设置')
   self.assertTrue(result['ok']);self.assertTrue(any(s['guid']=='GUID-TEST' for s in result['sources']))
   self.assertIn('verified reference area',result['context']);self.assertNotIn('do not expose this script',result['context'])
 def test_missing_help_is_disclosed(self):
  with patch.dict(os.environ,{'GOSTAR_HELP_ROOT':'/nonexistent-gostar-help'}):
   result=help_local.lookup('创建力矩系数报告')
  self.assertIn('reference length',result['context']);self.assertTrue(result['warnings'])
  self.assertFalse(any(s['guid'] for s in result['sources']))
 def test_runtime_relocated(self):
  import shutil
  with tempfile.TemporaryDirectory(prefix='GoStar relocated ') as temp:
   root=Path(temp);shutil.copytree(ROOT/'runtime/knowledge',root/'knowledge');(root/'scripts').mkdir()
   for name in ('starccm_skill_bridge.py','gostar_local_help.py'):shutil.copyfile(ROOT/'scripts'/name,root/'scripts'/name)
   env={**os.environ,'GOSTAR_HELP_ROOT':'/nonexistent-gostar-help'}
   run=subprocess.run([sys.executable,str(root/'scripts/starccm_skill_bridge.py'),'创建力矩系数报告'],env=env,capture_output=True,check=True,text=True)
   data=json.loads(run.stdout);self.assertTrue(data['ok']);self.assertIn('GoStar',data['context'])
 def test_source_brand_and_integration(self):
  core=(ROOT/'CodexAssistant.java').read_text();top=(ROOT/'plugin/src/local/starccm/assistant/AssistantTopComponent.java').read_text()
  for old in ('STAR Codex 助手','STAR AI','starccm-codex-chat.txt'):self.assertNotIn(old,core+top)
  self.assertIn('create_aerodynamic_report',core);self.assertIn('GoStar-chat.txt',core)
  self.assertIn('Codex CLI',core)  # real provider name is preserved
 def test_distribution(self):
  archive=ROOT/'dist/GoStar-0.6.1.zip'
  self.assertTrue(archive.is_file(),'Build the public package first')
  import hashlib
  with zipfile.ZipFile(archive) as z:
   prefix='GoStar-0.6.1/';manifest=json.loads(z.read(prefix+'MANIFEST.json'))
   for rel,digest in manifest['sha256'].items():self.assertEqual(hashlib.sha256(z.read(prefix+rel)).hexdigest(),digest)
   for name in z.namelist():
    self.assertNotIn('star-ccm-skill/',name);self.assertFalse(name.lower().endswith(('.sim','.step','.stp','.pem','.key','.log')))
   with zipfile.ZipFile(io.BytesIO(z.read(prefix+'plugin/GoStar-0.6.1.nbm'))) as nbm:
    self.assertIn(b'GoStar',nbm.read('Info/info.xml'))
    with zipfile.ZipFile(io.BytesIO(nbm.read('netbeans/modules/local-starccm-assistant.jar'))) as jar:
     self.assertIn('local/starccm/assistant/AerodynamicReports.class',jar.namelist());self.assertFalse(any('NativeSmoke' in n or '061Native' in n or '061Readback' in n for n in jar.namelist()))
     self.assertEqual(jar.read('local/starccm/assistant/installation.properties'),b'assistant.home=\n')
 def test_readme_local_links(self):
  for rel in ('README.md','README.zh-CN.md','docs/INSTALL.md','docs/REPORTS.md'):
   path=ROOT/rel
   for link in re.findall(r'\]\(([^)]+)\)',path.read_text()):
    if not link.startswith(('http:','https:','#')):self.assertTrue((path.parent/link.split('#')[0]).is_file(),f'Broken link {rel}: {link}')
if __name__=='__main__':unittest.main()
