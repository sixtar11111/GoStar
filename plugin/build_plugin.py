"""Build an installable NBM using the local STAR JDK and NetBeans libraries.

No STAR jars are redistributed. The existing macro source is packaged in a
namespace at build time so macro and native window use the same implementation.
"""
from __future__ import annotations
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parent.parent
CODE_NAME = "local.starccm.assistant"
JAR_NAME = "local-starccm-assistant.jar"
VERSION = "0.6.1"
DEPENDENCIES = "star.coremodule > 19.4.9, org.openide.windows > 6.79, org.openide.util > 9.10, org.openide.util.lookup > 8.36"

def manifest_bytes(values: dict[str, str]) -> bytes:
    lines = []
    for key, value in values.items():
        data = f"{key}: {value}".encode("utf-8")
        first = True
        while data:
            size = 70 if first else 69
            # All manifest values here are ASCII; UTF-8 display text lives in XML.
            lines.append((b"" if first else b" ") + data[:size])
            data = data[size:]
            first = False
    return b"\r\n".join(lines) + b"\r\n\r\n"

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--star-home", default=os.environ.get("STARCCM_HOME", "/opt/Siemens/19.04.009-R8/STAR-CCM+19.04.009-R8"))
    parser.add_argument("--jdk", default=os.environ.get("STARCCM_JDK"))
    args = parser.parse_args()
    star = Path(args.star_home)
    jdk = Path(args.jdk) if args.jdk else star.parent / "jdk/linux-x86_64/jdk21.0.1"
    compiler = jdk / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    platform = star / "star/lib/java/platform"
    if not compiler.is_file() or not (platform / "modules/ext/starbase.jar").is_file():
        parser.error("请通过 --star-home 和 --jdk 指定 STAR 2406 与 Java 21 路径")
    jars = sorted(platform.glob("lib/*.jar")) + sorted(platform.glob("modules/*.jar")) + sorted(platform.glob("modules/ext/*.jar"))
    attributes = {
        "Manifest-Version": "1.0",
        "OpenIDE-Module": CODE_NAME,
        "OpenIDE-Module-Name": "GoStar",
        "OpenIDE-Module-Display-Category": "Simcenter STAR-CCM+",
        "OpenIDE-Module-Specification-Version": VERSION,
        "OpenIDE-Module-Module-Dependencies": DEPENDENCIES,
        "OpenIDE-Module-Java-Dependencies": "Java > 21",
        "OpenIDE-Module-Requires": "org.openide.windows.WindowManager",
        "OpenIDE-Module-Public-Packages": "-",
    }
    output = ROOT / "build/plugin"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="star-plugin-build-") as temporary:
        work = Path(temporary)
        generated = work / "CodexAssistant.java"
        generated.write_text("package local.starccm.assistant;\n" + (ROOT / "CodexAssistant.java").read_text(), encoding="utf-8")
        classes = work / "classes"
        classes.mkdir()
        sources = [generated, *sorted((ROOT / "plugin/src").rglob("*.java"))]
        subprocess.run([str(compiler), "-proc:none", "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, jars)), "-d", str(classes), *map(str, sources)], check=True)
        jar = output / JAR_NAME
        with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
            directories = {"META-INF/"}
            for base in (classes, ROOT / "plugin/resources"):
                directories.update(item.relative_to(base).as_posix() + "/" for item in base.rglob("*") if item.is_dir())
            for directory in sorted(directories): archive.writestr(directory, b"")
            archive.writestr("META-INF/MANIFEST.MF", manifest_bytes(attributes))
            archive.writestr("META-INF/generated-layer.xml", (ROOT / "plugin/resources/local/starccm/assistant/layer.xml").read_bytes())
            for base in (classes, ROOT / "plugin/resources"):
                for item in sorted(base.rglob("*")):
                    if item.is_file(): archive.write(item, item.relative_to(base).as_posix())
            # Distribution must not depend on the build machine's project path.
            archive.writestr("local/starccm/assistant/installation.properties", "assistant.home=\n")
        config = ('<?xml version="1.0" encoding="UTF-8"?>\n'
                  '<!DOCTYPE module PUBLIC "-//NetBeans//DTD Module Status 1.0//EN" "http://www.netbeans.org/dtds/module-status-1_0.dtd">\n'
                  f'<module name="{CODE_NAME}">\n'
                  '<param name="autoload">false</param><param name="eager">false</param>'
                  '<param name="enabled">true</param>'
                  f'<param name="jar">modules/{JAR_NAME}</param>\n</module>\n')
        info_attrs = {k: v for k, v in attributes.items() if k.startswith("OpenIDE-Module") and k not in ("OpenIDE-Module-Layer", "OpenIDE-Module-Public-Packages")}
        info_attrs["OpenIDE-Module-Short-Description"] = "GoStar natural-language assistant for STAR-CCM+ 2406"
        xml_attrs = " ".join(f"{k}={quoteattr(v)}" for k, v in info_attrs.items())
        info = ('<?xml version="1.0" encoding="UTF-8"?>\n'
                '<!DOCTYPE module PUBLIC "-//NetBeans//DTD Autoupdate Module Info 2.5//EN" "http://www.netbeans.org/dtds/autoupdate-info-2_5.dtd">\n'
                f'<module codenamebase="{CODE_NAME}" distribution="{CODE_NAME}-{VERSION}.nbm" license="local-license" downloadsize="0">'
                f'<manifest {xml_attrs}/><license name="local-license">Local project plugin. STAR libraries are not included.</license></module>')
        nbm = output / f"{CODE_NAME}-{VERSION}.nbm"
        with zipfile.ZipFile(nbm, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("Info/info.xml", info)
            archive.write(jar, "netbeans/modules/" + JAR_NAME)
            archive.writestr("netbeans/config/Modules/local-starccm-assistant.xml", config)
        import shutil
        branded = output / f"GoStar-{VERSION}.nbm"
        shutil.copyfile(nbm,branded)
        print(f"PLUGIN_BUILT {branded}")

if __name__ == "__main__":
    main()
