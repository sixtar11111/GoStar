"""Build the public GoStar ZIP from an explicit allowlist; no vendor docs or credentials."""
from __future__ import annotations
import hashlib, importlib.util, io, json, shutil, tempfile, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('plugin_builder',ROOT/'plugin/build_plugin.py')
builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
VERSION=builder.VERSION

def put(source:Path,target:Path):
    if not source.is_file() or source.is_symlink():raise ValueError('Missing/unsafe release input: '+str(source))
    target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)

def main():
    nbm=ROOT/f'build/plugin/GoStar-{VERSION}.nbm'
    if not nbm.is_file():raise SystemExit('Build first: python3 plugin/build_plugin.py')
    with zipfile.ZipFile(nbm) as outer,zipfile.ZipFile(io.BytesIO(outer.read('netbeans/modules/local-starccm-assistant.jar'))) as jar:
        assert jar.read('local/starccm/assistant/installation.properties')==b'assistant.home=\n'
        assert not any('NativeSmoke' in p or 'Readback.class' in p for p in jar.namelist()),'Test classes must not ship'
    output=ROOT/'dist';output.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='gostar-package-') as temp:
        folder=Path(temp)/f'GoStar-{VERSION}'
        put(nbm,folder/'plugin'/nbm.name)
        for rel in ('README.md','README.zh-CN.md','docs/INSTALL.md','docs/REPORTS.md','docs/VALIDATION_061.md'):
            put(ROOT/rel,folder/rel)
        for name in ('starccm_skill_bridge.py','gostar_local_help.py'):
            put(ROOT/'scripts'/name,folder/'runtime/scripts'/name)
        for name in ('reports.md','external-flow.md','operations.md'):
            put(ROOT/'runtime/knowledge'/name,folder/'runtime/knowledge'/name)
        put(ROOT/'packaging/verify_package.py',folder/'verify_package.py')
        files=sorted(p for p in folder.rglob('*') if p.is_file())
        manifest={'package':'GoStar','version':VERSION,'api_baseline':'2406.0001 / 19.04.009-R8','vendor_documentation':'not redistributed; optional local installed help','sha256':{p.relative_to(folder).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
        (folder/'MANIFEST.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        archive=output/f'GoStar-{VERSION}.zip'
        with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
            for p in sorted(folder.rglob('*')):
                if p.is_file():
                    info=zipfile.ZipInfo((Path(folder.name)/p.relative_to(folder)).as_posix(),date_time=(2026,11,13,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;z.writestr(info,p.read_bytes())
    put(nbm,output/nbm.name)
    (output/'SHA256SUMS.txt').write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  '+archive.name+'\n'+hashlib.sha256(nbm.read_bytes()).hexdigest()+'  '+nbm.name+'\n',encoding='ascii')
    print(f'PACKAGE_BUILT {archive} bytes={archive.stat().st_size}')
if __name__=='__main__':main()
