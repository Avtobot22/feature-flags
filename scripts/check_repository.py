"""Check tracked text files without external dependencies (Python 3.10+)."""

from pathlib import Path
import re
import subprocess
import sys


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    paths = subprocess.check_output(
        ["git", "ls-files", "-z"], cwd=root
    ).decode("utf-8").split("\0")
    errors = []
    for name in filter(None, paths):
        path = root / name
        if path.is_symlink():
            continue
        data = path.read_bytes()
        if b"\0" in data:  # Binary assets, including Gradle wrapper JARs.
            continue
        try:
            content = data.decode("utf-8")
        except UnicodeDecodeError:
            errors.append(f"{name}: expected UTF-8 text")
            continue
        if name.endswith(".bat"):
            content = content.replace("\r\n", "\n")
        if "\r" in content:
            errors.append(f"{name}: expected LF line endings")
        if content and not content.endswith("\n"):
            errors.append(f"{name}: missing final newline")
        for number, line in enumerate(content.splitlines(), 1):
            if line.rstrip(" \t") != line:
                errors.append(f"{name}:{number}: trailing whitespace")
            if re.match(r"^(<{7}|={7}|>{7}|\|{7})(?: |$)", line):
                errors.append(f"{name}:{number}: unresolved merge marker")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Repository checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
