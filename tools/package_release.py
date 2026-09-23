"""Build release assets from a clean Git commit using Python 3.9+, JDK 17 and Inno Setup.

Example: python tools/package_release.py --jdk C:/jdk-17 --inno C:/Inno/ISCC.exe
Outputs go to a fresh sibling dw6318-releases directory. Previous runs are retained.
The source manifest pins the bundled runtime and supplemental dependency sources.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def execute(args, capture=False):
    return subprocess.run([str(a) for a in args], cwd=ROOT, check=True,
                          text=True, stdout=subprocess.PIPE if capture else None).stdout


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def zip_directory(source, target, prefix=""):
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                archive.write(path, prefix + path.relative_to(source).as_posix())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--inno", type=Path, required=True, help="Path to ISCC.exe")
    parser.add_argument("--cache", type=Path, default=ROOT.parent / "release-source-cache")
    parser.add_argument("--output", type=Path, help="Must not already exist")
    parser.add_argument("--native-version", default="0.29.1")
    args = parser.parse_args()
    args.jdk = args.jdk.resolve()
    args.inno = args.inno.resolve()
    args.cache = args.cache.resolve()
    for tool in (args.jdk / "bin/java.exe", args.jdk / "bin/jpackage.exe", args.inno):
        if not tool.is_file():
            raise ValueError(f"Required tool not found: {tool}")
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.native_version):
        raise ValueError("Native version must have three numeric components")
    if execute(["git", "status", "--porcelain"], capture=True).strip():
        raise ValueError("Commit the source before packaging so the source archive matches the binaries")
    commit = execute(["git", "rev-parse", "HEAD"], capture=True).strip()
    java = (ROOT / "src/chatty/Chatty.java").read_text(encoding="utf-8")
    version = re.search(r'public static final String VERSION = "([^"]+)";', java).group(1)
    manifest = json.loads((ROOT / "tools/release-sources.json").read_text(encoding="utf-8"))
    release_info = (args.jdk / "release").read_text(encoding="utf-8")
    if f'IMPLEMENTOR_VERSION="{manifest["runtime"]}"' not in release_info:
        raise ValueError("JDK does not match the runtime source pinned in release-sources.json")
    run_name = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]
    output = (args.output or ROOT.parent / "dw6318-releases" / run_name).resolve()
    output.mkdir(parents=True, exist_ok=False)
    dist = output / "release"
    dist.mkdir()
    args.cache.mkdir(parents=True, exist_ok=True)
    print(f"Release work directory: {output}", flush=True)

    # Keep source inputs available next to the binaries; the runtime has its own source asset.
    sources = []
    for entry in manifest["sources"]:
        path = args.cache / entry["name"]
        if path.exists():
            if sha256(path) != entry["sha256"]:
                raise ValueError(f"Cached source checksum differs: {path}; use a fresh cache")
        else:
            print(f"Downloading {entry['name']}", flush=True)
            urllib.request.urlretrieve(entry["url"], path)
            if sha256(path) != entry["sha256"]:
                raise ValueError(f"Downloaded source checksum differs: {path}")
        sources.append((entry, path))

    build_dir = output / "build"
    init = output / "release.init.gradle"
    init.write_text("gradle.beforeProject { project ->\n"
                    "    project.layout.buildDirectory.set(new File(\n"
                    "        gradle.startParameter.projectProperties.get('chattyForkBuildDir')))\n"
                    "}\n", encoding="utf-8")
    execute([args.jdk / "bin/java.exe", f"-Dorg.gradle.java.home={args.jdk}",
             "-classpath", ROOT / "gradle/wrapper/gradle-wrapper.jar",
             "org.gradle.wrapper.GradleWrapperMain", "build", "shadowJar", "allPlatformsZip",
             "--no-daemon", "--console=plain", "--project-dir", ROOT,
             "--project-cache-dir", output / "gradle-cache", "--init-script", init,
             f"-PchattyForkBuildDir={build_dir}"])
    jar = build_dir / "libs/Chatty.jar"
    shutil.copy2(jar, dist / f"Chatty_{version}.jar")
    shutil.copy2(build_dir / f"releases/Chatty_{version}.zip", dist / f"Chatty_{version}.zip")

    input_dir = output / "jpackage-input"
    input_dir.mkdir()
    shutil.copy2(jar, input_dir / "Chatty.jar")
    image_root = output / "windows"
    execute([args.jdk / "bin/jpackage.exe", "--type", "app-image", "--name", "Chatty-dw6318",
             "--input", input_dir, "--dest", image_root, "--main-jar", "Chatty.jar",
             "--main-class", "chatty.Chatty2", "--app-version", args.native_version,
             "--vendor", "dw6318", "--description", "Chatty dw6318 - Twitch Chat Client",
             "--icon", ROOT / "assets-bundle/Chatty.ico", "--java-options", "-Xmx600M",
             "--java-options", "-Dsun.java2d.d3d=false", "--arguments", "-abc",
             "--add-launcher", f"ChattyPortable={ROOT / 'assets-bundle/LauncherPortable.properties'}"])
    app = image_root / "Chatty-dw6318"
    for name in ("LICENSE", "APACHE_LICENSE", "LGPL", "THIRD_PARTY_NOTICES.md"):
        shutil.copy2(ROOT / name, app / name)
    for name in ("sounds", "img"):
        shutil.copytree(ROOT / "assets" / name, app / "app" / name)
    shutil.copytree(ROOT / "assets-bundle/fallback-fonts", app / "runtime/lib/fonts/fallback")
    source_notice = ("Chatty dw6318 " + version + "\nSource commit: " + commit + "\n"
                     "Source and binary downloads: https://github.com/dw6318/chatty/releases/tag/v" + version + "\n"
                     "Application source: Chatty_" + version + "_source.zip\n"
                     "Additional library/font sources: Chatty_" + version + "_third-party-source.zip\n"
                     "Bundled Java source: " + manifest["runtime_source_name"] + "\n")
    (app / "SOURCE.txt").write_text(source_notice, encoding="utf-8")
    zip_directory(app, dist / f"Chatty_{version}_win_x64_portable.zip", "Chatty-dw6318/")
    execute([args.inno, "/Qp", f"/DAppVersion={version}", f"/DNativeVersion={args.native_version}",
             f"/DAppImage={app}", f"/DReleaseDir={dist}", f"/DSourceRoot={ROOT}", ROOT / "setup/dw6318.iss"])

    execute(["git", "archive", "--format=zip", "--prefix=Chatty-source/",
             "--output", dist / f"Chatty_{version}_source.zip", commit])
    with zipfile.ZipFile(dist / f"Chatty_{version}_third-party-source.zip", "w", zipfile.ZIP_STORED) as archive:
        archive.write(ROOT / "tools/release-sources.json", "release-sources.json")
        for entry, path in sources:
            if entry["role"] == "runtime-source":
                shutil.copy2(path, dist / path.name)
            else:
                archive.write(path, path.name)
    (dist / "SOURCE.txt").write_text(source_notice, encoding="utf-8")
    checksums = "".join(sha256(p) + "  " + p.name + "\n" for p in sorted(dist.iterdir()) if p.is_file())
    (dist / "SHA256SUMS.txt").write_text(checksums, encoding="utf-8")
    (output / "build-result.json").write_text(json.dumps({"commit": commit, "version": version,
        "release": str(dist), "appImage": str(app), "build": str(build_dir)}, indent=2), encoding="utf-8")
    print(f"Release assets ready: {dist}", flush=True)


if __name__ == "__main__":
    main()
