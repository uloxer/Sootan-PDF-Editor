"""Measure versioned content and build artifacts without generated files or Git history."""
from pathlib import Path
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASELINE = "684608d143779bf3a8ffc67639f077a1202c62ff"
LANGUAGES = {".java", ".ts", ".tsx", ".py", ".rs"}


def primary(name):
    return Path(name).suffix in LANGUAGES and (
        (name.startswith("app/") and "/src/main/java/" in name)
        or name.startswith(("frontend/src/", "engine/src/", "frontend/src-tauri/src/"))
    )


def totals(entries):
    return {
        "all_versioned_files": len(entries),
        "all_versioned_bytes": sum(size for _, size in entries),
        "primary_code_files": sum(primary(name) for name, _ in entries),
        "primary_code_bytes": sum(size for name, size in entries if primary(name)),
        "all_language_bytes_including_tests": sum(
            size for name, size in entries if Path(name).suffix in LANGUAGES
        ),
    }


def main():
    listing = subprocess.check_output(
        ["git", "ls-tree", "-r", "-l", BASELINE], cwd=ROOT, text=True
    )
    before = []
    for line in listing.splitlines():
        metadata, name = line.split("\t", 1)
        before.append((name, int(metadata.split()[3])))
    staged = subprocess.check_output(
        ["git", "ls-files", "--stage", "-z"], cwd=ROOT
    ).decode().split("\0")
    indexed = []
    for entry in staged:
        if entry:
            metadata, name = entry.split("\t", 1)
            indexed.append((name, metadata.split()[1]))
    sizes = subprocess.check_output(
        ["git", "cat-file", "--batch-check=%(objectsize)"],
        input="\n".join(oid for _, oid in indexed) + "\n", cwd=ROOT, text=True
    ).splitlines()
    after = [(name, int(size)) for (name, _), size in zip(indexed, sizes)]
    artifacts = {}
    for relative in ["frontend/dist", "app/core/build/libs"]:
        target = ROOT / relative
        artifacts[relative] = (
            sum(p.stat().st_size for p in target.rglob("*") if p.is_file())
            if target.exists() else None
        )
    print(json.dumps({"baseline": BASELINE, "before": totals(before),
                      "after": totals(after), "artifacts_bytes": artifacts}, indent=2))


if __name__ == "__main__":
    main()
