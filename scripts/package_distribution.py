"""Create the source-free, deterministic professor ZIP (maintainer tooling only)."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def package(output):
    release = json.loads((ROOT / "distribution/release.json").read_text())
    version = release["version"]
    if not re.fullmatch(r"v\d+\.\d+\.\d+", version):
        raise ValueError("A fixed vMAJOR.MINOR.PATCH version is required")
    files = {name: (ROOT / "distribution" / name).read_text(encoding="utf-8").replace("\r\n", "\n")
             for name in ("compose.yaml", "README.md")}
    references = re.findall(r"^\s+image:\s*(\S+)\s*$", files["compose.yaml"], re.M)
    expected = {f'{release["registry"]}/{release["owner"]}/{image}:{version}' for image in release["images"].values()}
    if set(references) != expected or len(references) != 3 or re.search(r"^\s*build:", files["compose.yaml"], re.M):
        raise ValueError("Delivery Compose must contain exactly the three fixed registry images and no build")
    if version not in files["README.md"]:
        raise ValueError("README does not describe the configured version")
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"gamestats-{version}.zip"
    temporary = archive.with_suffix(".zip.tmp")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
            for name, content in sorted(files.items()):
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                bundle.writestr(info, content.encode("utf-8"))
        if archive.exists() and archive.read_bytes() != temporary.read_bytes():
            raise ValueError(f"Refusing to replace a different ZIP for {version}; use a new version or output directory")
        temporary.replace(archive)
    finally:
        temporary.unlink(missing_ok=True)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix(".zip.sha256").write_text(f"{digest}  {archive.name}\n", encoding="ascii")
    print(json.dumps({"archive": str(archive), "sha256": digest, "files": sorted(files), "images": references}, indent=2))
    return archive


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    package(parser.parse_args().output.resolve())
