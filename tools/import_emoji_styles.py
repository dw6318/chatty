"""Rebuild bundled styles from pinned upstream sources; never overwrite an output.

Requires Python 3.9+ and Pillow. Source revisions and archive hash are pinned below.
This imports image assets, not fonts. Missing sequences remain runtime fallbacks.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

from PIL import Image

FLUENT_COMMIT = "1ffb34c752ecf5d402f04cfb4b392c77f57c54bc"
NOTO_COMMIT = "06121655d0e82f9cae6e7ba6feed4fa6fdbfc2a4"
OPENMOJI_VERSION = "17.0.0"
OPENMOJI_SHA256 = "fee0c272d0105f5b37e63dcc60c3759bda5a90c5ba2b160f64eaa768149eb5e9"
TONES = {"Light": "1F3FB", "Medium-Light": "1F3FC", "Medium": "1F3FD",
         "Medium-Dark": "1F3FE", "Dark": "1F3FF"}


def normalize(points):
    return tuple(f"{int(p, 16):X}" for p in points if int(p, 16) != 0xFE0F)


def pinned_checkout(path, commit):
    actual = subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()
    if actual != commit:
        raise ValueError(f"Expected {commit} at {path}; got {actual}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fluent", required=True, type=Path)
    parser.add_argument("--noto", required=True, type=Path)
    parser.add_argument("--openmoji", required=True, type=Path, help="17.0.0 color 72x72 ZIP")
    parser.add_argument("--openmoji-license", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    pinned_checkout(args.fluent, FLUENT_COMMIT)
    pinned_checkout(args.noto, NOTO_COMMIT)
    if hashlib.sha256(args.openmoji.read_bytes()).hexdigest() != OPENMOJI_SHA256:
        raise ValueError("OpenMoji ZIP does not match the pinned 17.0.0 release")
    repo = Path(__file__).resolve().parents[1]
    reference = repo / "test/chatty/gui/emoji/emoji-test-17.0.txt"
    sequences = set()
    for line in reference.read_text(encoding="utf-8").splitlines():
        if re.match(r"^[0-9A-F]+ ", line):
            sequences.add(normalize(line.split(";")[0].split()))
    for style in ("noto", "fluent", "openmoji"):
        if (args.output / style).exists():
            raise ValueError(f"Refusing to overwrite {args.output / style}; use a fresh output directory")

    variants = {}
    for sequence in sequences:
        base = tuple(p for p in sequence if p not in TONES.values())
        variants.setdefault(base, []).append(sequence)

    sources = {"noto": {}, "fluent": {}, "openmoji": {}}
    for png in sorted((args.noto / "2D/png/128").glob("emoji_u*.png")):
        sequence = normalize(png.stem.removeprefix("emoji_u").split("_"))
        if sequence in sequences:
            sources["noto"][sequence] = (png.read_bytes(), png.relative_to(args.noto).as_posix())
    for metadata in sorted((args.fluent / "assets").glob("*/metadata.json")):
        data = json.loads(metadata.read_text(encoding="utf-8"))
        base = normalize(data["unicode"].split())
        for png in sorted(metadata.parent.glob("**/3D/*.png")):
            folder = png.parent.parent.name
            tone = TONES.get(folder)
            candidates = [base] if tone is None else [
                sequence for sequence in variants.get(base, [])
                if {p for p in sequence if p in TONES.values()} == {tone}]
            for sequence in candidates:
                if sequence in sequences:
                    sources["fluent"][sequence] = (png.read_bytes(), png.relative_to(args.fluent).as_posix())
    with zipfile.ZipFile(args.openmoji) as archive:
        for name in sorted(archive.namelist()):
            filename = Path(name)
            if filename.suffix.lower() != ".png" or not re.fullmatch(r"[0-9A-Fa-f-]+", filename.stem):
                continue
            sequence = normalize(filename.stem.split("-"))
            if sequence in sequences:
                sources["openmoji"][sequence] = (archive.read(name), name)

    notices = {
        "fluent": (
            "Microsoft Fluent Emoji 3D. Copyright (c) Microsoft Corporation.\n"
            f"Source: https://github.com/microsoft/fluentui-emoji/tree/{FLUENT_COMMIT}\n"
            "License: MIT; see LICENSE.txt.\n"
            "Changes: PNGs resized to fit 72x72, preserving aspect ratio and alpha; filenames mapped to Unicode.\n"),
        "noto": (
            "Google Noto Emoji 2D image assets. Copyright 2013 Google, Inc. All Rights Reserved.\n"
            f"Source: https://github.com/googlefonts/noto-emoji/tree/{NOTO_COMMIT}\n"
            "License: Apache-2.0 for image resources; see LICENSE.txt and UPSTREAM-NOTICE.txt.\n"
            "Flags have separate provenance; see FLAGS-LICENSE.txt and FLAGS-README.md. No font files included.\n"
            "Changes: PNGs resized to fit 72x72, preserving aspect ratio and alpha; filenames mapped to Unicode.\n"),
        "openmoji": (
            "All OpenMoji emoji designed by OpenMoji - the open-source emoji and icon project.\n"
            "Copyright HfG Schwaebisch Gmuend, Benedikt Gross, Daniel Utz and the OpenMoji contributors.\n"
            f"Source: https://github.com/hfg-gmuend/openmoji/releases/tag/{OPENMOJI_VERSION}\n"
            "License: CC-BY-SA-4.0; see LICENSE.txt and https://creativecommons.org/licenses/by-sa/4.0/\n"
            "Changes: selected Unicode emoji PNGs from the color 72x72 release; image bytes unchanged; filenames normalized.\n"
            "These graphics retain CC-BY-SA-4.0; they are not relicensed as GPL.\n")}

    for style, images in sources.items():
        if len(images) < 2000:
            raise ValueError(f"Incomplete {style} input: only {len(images)} matching images")
        target = args.output / style
        (target / "72x72").mkdir(parents=True)
        rows = []
        manifest = ["# File\tOriginal source path\tOriginal SHA256\tBundled SHA256"]
        for sequence, (data, source_path) in sorted(images.items()):
            filename = "-".join(p.lower() for p in sequence) + ".png"
            original_hash = hashlib.sha256(data).hexdigest()
            if style != "openmoji":
                with Image.open(io.BytesIO(data)) as original:
                    image = original.convert("RGBA")
                    image.thumbnail((72, 72), Image.Resampling.LANCZOS)
                    output = io.BytesIO()
                    image.save(output, format="PNG", optimize=True)
                    data = output.getvalue()
            (target / "72x72" / filename).write_bytes(data)
            rows.append(" ".join(sequence) + "\t" + filename)
            manifest.append("\t".join((filename, source_path, original_hash, hashlib.sha256(data).hexdigest())))
        (target / "emoji.tsv").write_text("# Normalized code points\tPNG filename\n" + "\n".join(rows) + "\n", encoding="utf-8")
        (target / "ASSET-MANIFEST.tsv").write_text("\n".join(manifest) + "\n", encoding="utf-8")
        (target / "NOTICE.txt").write_text(notices[style], encoding="utf-8")
        print(f"{style}: {len(images)} distinct images", flush=True)

    shutil.copyfile(args.fluent / "LICENSE", args.output / "fluent/LICENSE.txt")
    shutil.copyfile(repo / "APACHE_LICENSE", args.output / "noto/LICENSE.txt")
    shutil.copyfile(args.noto / "2D/svg/LICENSE", args.output / "noto/UPSTREAM-NOTICE.txt")
    shutil.copyfile(args.noto / "README.md", args.output / "noto/UPSTREAM-README.md")
    for source, target in [("LICENSE", "FLAGS-LICENSE.txt"), ("README.md", "FLAGS-README.md")]:
        shutil.copyfile(args.noto / "third_party/region-flags" / source, args.output / "noto" / target)
    shutil.copyfile(args.openmoji_license, args.output / "openmoji/LICENSE.txt")


if __name__ == "__main__":
    main()
