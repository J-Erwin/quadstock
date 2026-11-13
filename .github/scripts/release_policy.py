"""Select CI versions and reject release tags outside main before building/publishing."""
import os
from pathlib import Path
import re
import subprocess

STABLE = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"


def select(base, event, ref, sha, run, attempt):
    if not re.fullmatch(STABLE, base):
        raise ValueError("Project version must be a stable X.Y.Z version")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected a full Git commit SHA")
    if not run.isdigit() or not attempt.isdigit():
        raise ValueError("Expected numeric run identifiers")
    if event == "push" and ref.startswith("refs/tags/"):
        if ref != "refs/tags/v" + base:
            raise ValueError("Release tag must exactly match v<project version>")
        return base, True
    if event == "pull_request":
        channel = "pr"
    elif event in ("push", "workflow_dispatch") and ref in ("refs/heads/dev", "refs/heads/main"):
        if ref == "refs/heads/main":
            return base, False
        channel = "dev"
    else:
        raise ValueError("Only dev/main branches and matching release-tag pushes are supported")
    return f"{base}-{channel}.{run}.{attempt}+{sha[:12]}", False


def require_main(commit):
    subprocess.run(
        ["git", "merge-base", "--is-ancestor", commit, "refs/remotes/origin/main"],
        check=True,
    )


def main():
    matches = re.findall(r'^version = "([^"]+)"$', Path("build.gradle.kts").read_text(), re.M)
    if len(matches) != 1:
        raise ValueError("Expected one literal project version in build.gradle.kts")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    version, release = select(matches[0], os.environ["GITHUB_EVENT_NAME"],
                              os.environ["GITHUB_REF"], commit,
                              os.environ["GITHUB_RUN_NUMBER"], os.environ["GITHUB_RUN_ATTEMPT"])
    if release:
        require_main(commit)
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"version={version}\nrelease={str(release).lower()}\ncommit={commit}\n")
    print(f"Version: {version}; publish release: {release}")


if __name__ == "__main__":
    main()
