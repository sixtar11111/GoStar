#!/usr/bin/env bash
set -euo pipefail
test_source="$(cd "$(dirname "$0")/.." && pwd)"
test_output="${1:-/tmp/starccm-capabilities060/reports061-acceptance}"
test_nbm="${2:-$test_source/build/plugin/local.starccm.assistant-0.6.2.nbm}"
python3 - "$test_source" "$test_output" "$test_nbm" <<'PY'
from pathlib import Path
import hashlib,json,os,signal,subprocess,sys,time,zipfile
source,root,nbm=map(lambda x:Path(x).resolve(),sys.argv[1:]);root.mkdir(parents=True,exist_ok=True)
if (root/'run-receipt.json').exists():raise SystemExit('Refusing to overwrite prior native acceptance output; select a fresh output directory')
profile=root/'profile';classes=root/'test-classes';classes.mkdir()
starhome=Path(os.environ.get('STARCCM_HOME','/opt/Siemens/19.04.009-R8/STAR-CCM+19.04.009-R8'));jdk=Path(os.environ.get('STARCCM_JDK',str(starhome.parent/'jdk/linux-x86_64/jdk21.0.1')))/'bin';platform=starhome/'star/lib/java/platform'
with zipfile.ZipFile(nbm) as z:
 for name in z.namelist():
  if name.startswith('netbeans/') and not name.endswith('/'):
   p=profile/name[len('netbeans/'):];p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(name))
jar=profile/'modules/local-starccm-assistant.jar';classpath=str(jar)+':'+':'.join(str(platform/x/'*') for x in ('lib','modules','modules/ext'))
subprocess.run([str(jdk/'javac'),'-proc:none','-encoding','UTF-8','-cp',classpath,'-d',str(classes),str(source/'tests/AerodynamicReports061Native.java'),str(source/'tests/AerodynamicReports061Readback.java')],check=True)
with zipfile.ZipFile(jar,'a',zipfile.ZIP_DEFLATED) as z:
 for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
record={'started_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'release_nbm_sha256':hashlib.sha256(nbm.read_bytes()).hexdigest(),'release_nbm_unmodified':True,'fixture_class_injection_only_in_isolated_profile':True,'selected_provider_API_calls':0,'opened_user_input_simulations':False,'runs':[]}
(root/'run-receipt.json').write_text(json.dumps(record,indent=2))
def run(label,test_class,load=None,string_output=False):
 run_dir=root/label;run_dir.mkdir();macro=run_dir/'Api060Bootstrap.java'
 macro.write_text('''import star.common.*; import org.openide.modules.ModuleInfo; import org.openide.util.Lookup; import java.nio.file.*;
public class Api060Bootstrap extends StarMacro { public void execute(){Path out=Path.of(System.getenv("STAR_API060_OUTPUT"));try{ModuleInfo module=null;for(ModuleInfo m:Lookup.getDefault().lookupAll(ModuleInfo.class))if(m.getCodeNameBase().equals("local.starccm.assistant"))module=m;if(module==null||!module.isEnabled())throw new AssertionError("installed module unavailable");Class<?> test=Class.forName("local.starccm.assistant.'''+test_class+'''",true,module.getClassLoader());test.getMethod("run",Simulation.class,'''+('String.class' if string_output else 'Path.class')+''').invoke(null,getActiveSimulation(),'''+('out.toString()' if string_output else 'out')+''');Files.writeString(out.resolve("PASS.txt"),"PASS isolated native API060 acceptance");}catch(Throwable e){e.printStackTrace();try{Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();Files.writeString(out.resolve("FAILED.txt"),String.valueOf(cause));}catch(Exception ignored){}}} }
''')
 cmd=['/usr/bin/xvfb-run','-a','-s','-screen 0 1280x900x24',str(starhome/'star/bin/starccm+'),'-np','1','-noretry','-nbuserdir',str(profile)]
 cmd+=['-load',str(load)] if load else ['-new'];cmd+=['-macro',str(macro)]
 env=os.environ.copy();env['PATH']='/usr/bin:/bin:'+env.get('PATH','');env['STAR_API060_OUTPUT']=str(run_dir);env['OMP_NUM_THREADS']='1';env['JAVA_TOOL_OPTIONS']='-Djava.util.prefs.userRoot='+str(root/'prefs')+' -XX:ActiveProcessorCount=2'
 row={'label':label,'command':cmd,'started':time.time(),'independent_process':True};record['runs'].append(row);p=None
 try:
  with (run_dir/'star.log').open('w') as log:
   p=subprocess.Popen(cmd,cwd=run_dir,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);row['pid']=p.pid;print('STARTED '+label+' '+str(p.pid),flush=True)
   deadline=time.time()+240
   while time.time()<deadline and p.poll() is None and not (run_dir/'PASS.txt').exists() and not (run_dir/'FAILED.txt').exists():time.sleep(.5)
   row['passed']=(run_dir/'PASS.txt').exists();row['failure']=(run_dir/'FAILED.txt').read_text() if (run_dir/'FAILED.txt').exists() else (None if row['passed'] else 'Timed out or process exited before completion')
 finally:
  if p:
   try:os.killpg(p.pid,signal.SIGTERM)
   except ProcessLookupError:pass
   try:p.wait(timeout=10)
   except subprocess.TimeoutExpired:
    try:os.killpg(p.pid,signal.SIGKILL)
    except ProcessLookupError:pass
    p.wait(timeout=10)
  row['finished']=time.time();(root/'run-receipt.json').write_text(json.dumps(record,indent=2));print(json.dumps(row),flush=True)
 if not row['passed']:raise SystemExit('Native acceptance failed; see '+str(run_dir/'star.log'))
 return run_dir
write=run('report-write','AerodynamicReports061Native');saved=write/'reports061.sim';record['saved_sim_sha256']=hashlib.sha256(saved.read_bytes()).hexdigest();read=run('independent-reopen','AerodynamicReports061Readback',saved)
record['saved_sim_sha256_after_reopen']=hashlib.sha256(saved.read_bytes()).hexdigest();record['passed']=record['saved_sim_sha256']==record['saved_sim_sha256_after_reopen'];record['finished_utc']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime());(root/'run-receipt.json').write_text(json.dumps(record,indent=2));print(json.dumps({'passed':record['passed'],'output':str(root),'saved_sim_sha256':record['saved_sim_sha256']}),flush=True)
if not record['passed']:raise SystemExit('Read-only independent reopen changed saved SIM bytes')
PY
