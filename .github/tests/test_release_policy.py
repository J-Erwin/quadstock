import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release_policy", Path(__file__).parents[1] / "scripts/release_policy.py")
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class ReleasePolicyTest(unittest.TestCase):
    def select(self, event, ref, base="1.0.0"):
        return policy.select(base, event, ref, "a" * 40, "42", "2")

    def test_dev_and_pr_never_release(self):
        for event, ref, channel in [("push", "refs/heads/dev", "dev"),
                                    ("workflow_dispatch", "refs/heads/dev", "dev"),
                                    ("pull_request", "refs/pull/12/merge", "pr")]:
            self.assertEqual((f"1.0.0-{channel}.42.2+aaaaaaaaaaaa", False), self.select(event, ref))

    def test_main_requires_tag_to_publish(self):
        for event in ("push", "workflow_dispatch"):
            self.assertEqual(("1.0.0", False), self.select(event, "refs/heads/main"))
        self.assertEqual(("1.0.0", True), self.select("push", "refs/tags/v1.0.0"))

    def test_invalid_release_requests_fail(self):
        for event, ref in [("push", "refs/tags/v2.0.0"), ("push", "refs/tags/v1.0.0-rc.1"),
                           ("workflow_dispatch", "refs/tags/v1.0.0"), ("push", "refs/heads/feature")]:
            with self.assertRaises(ValueError):
                self.select(event, ref)
        with self.assertRaises(ValueError):
            self.select("push", "refs/heads/main", "01.0.0")

    def test_release_commit_must_be_reachable_from_main(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.check_output(["git", "-C", directory, *args], text=True, stderr=subprocess.DEVNULL).strip()
            git("init", "-q")
            git("config", "user.name", "CI Test")
            git("config", "user.email", "ci@example.invalid")
            git("commit", "--allow-empty", "-qm", "main")
            main = git("rev-parse", "HEAD")
            git("update-ref", "refs/remotes/origin/main", main)
            git("commit", "--allow-empty", "-qm", "dev only")
            dev = git("rev-parse", "HEAD")
            run = subprocess.run
            with patch.object(policy.subprocess, "run", side_effect=lambda args, **kw: run(args, cwd=directory, **kw)):
                policy.require_main(main)
                with self.assertRaises(subprocess.CalledProcessError):
                    policy.require_main(dev)
                git("update-ref", "refs/remotes/origin/main", dev)
                policy.require_main(main)
                policy.require_main(dev)


if __name__ == "__main__":
    unittest.main()
