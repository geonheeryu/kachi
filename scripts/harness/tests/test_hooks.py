"""PreToolUse·Stop 훅이 규약 위반을 exit 2로 차단하는지 검사한다."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import unittest

from helpers import SCRIPTS_DIR, Repo, sh

HOOKS_DIR = SCRIPTS_DIR / "hooks"


def run_hook(name: str, repo: Repo, payload: dict, env: dict | None = None) -> subprocess.CompletedProcess:
    """훅을 Claude Code처럼 stdin JSON으로 실행한다. HARNESS_* 변수는 바깥 세션의 값이 섞이지 않게 지운다."""
    base = {k: v for k, v in os.environ.items() if not k.startswith("HARNESS_")}
    merged = {**base, "CLAUDE_PROJECT_DIR": str(repo.root), **(env or {})}
    return subprocess.run(
        [sys.executable, str(HOOKS_DIR / name)], cwd=str(repo.root), input=json.dumps(payload),
        capture_output=True, text=True, env=merged,
    )


def bash(command: str, repo: Repo) -> dict:
    return {"hook_event_name": "PreToolUse", "tool_name": "Bash", "cwd": str(repo.root), "tool_input": {"command": command}}


def edit(path: str, repo: Repo, tool: str = "Edit") -> dict:
    return {"hook_event_name": "PreToolUse", "tool_name": tool, "cwd": str(repo.root), "tool_input": {"file_path": path}}


RUNNER_ENV = {"HARNESS_TASK": "1-demo", "HARNESS_PHASE": "1", "HARNESS_HEADLESS": "1"}


class PreToolUseBashTest(unittest.TestCase):
    def setUp(self):
        self.repo = Repo()
        self.addCleanup(self.repo.cleanup)

    def assert_blocked(self, command: str, fragment: str, env: dict | None = None):
        result = run_hook("pre_tool_use.py", self.repo, bash(command, self.repo), env)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn(fragment, result.stderr)

    def assert_allowed(self, command: str, env: dict | None = None):
        result = run_hook("pre_tool_use.py", self.repo, bash(command, self.repo), env)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_commit_and_push_are_blocked_only_in_runner_session(self):
        self.assert_blocked("git commit -m 'x'", "러너", RUNNER_ENV)
        self.assert_blocked("cd sub && git -C .. push origin main", "push", RUNNER_ENV)

    def test_commit_and_feature_branch_push_are_allowed_in_supervised_session(self):
        sh(["git", "checkout", "-q", "-b", "feat/x"], self.repo.root)
        self.assert_allowed("git commit -m 'x'")
        self.assert_allowed("git push -u origin feat/x")
        self.assert_allowed("git push")
        for command in (
            "git push -u origin feat/x && gh pr create --draft --base main --title 'fix: main 정리'",
            "git push origin feat/main-cleanup; git fetch origin main",
            "git push && gh api repos/o/r/issues/1/comments -f body=x",
            "git push | tail -f",
            "git commit -m 'push main -f'",
            "git checkout main && git pull",
            "git checkout -b feat/y main && git push -u origin feat/y",
            "git switch -c feat/y main && git push -u origin feat/y",
            "git commit -m 'fix -n option'",
            "git push -n origin feat/x",
        ):
            with self.subTest(command=command):
                self.assert_allowed(command)

    def test_merge_is_blocked(self):
        for command in (
            "gh pr merge 23 --squash",
            "gh -R o/r pr merge 23",
            "gh api -X PUT repos/o/r/pulls/23/merge",
            "gh api -X PUT repos/o/r/pulls/$PR/merge",
            "gh api graphql -f query='mutation { mergePullRequest(input: {}) { clientMutationId } }'",
            "gh api graphql -f query='mutation { enablePullRequestAutoMerge(input: {}) { clientMutationId } }'",
            "gh api -X POST repos/o/r/merges -f base=main -f head=feat/x",
        ):
            with self.subTest(command=command):
                self.assert_blocked(command, "병합")

    def test_push_to_main_is_blocked(self):
        sh(["git", "checkout", "-q", "-b", "feat/x"], self.repo.root)
        for command in (
            "git push origin main",
            "git push origin main; echo done",
            "git push origin 'HEAD:main'",
            "git push origin HEAD:refs/heads/main",
            "git -c push.default=current push origin main",
            "git --no-pager push origin feat/x:main",
            "git checkout main && git merge feat/x && git push",
            "(cd sub && git push origin main)",
            "git push origin main>/dev/null",
            "gh api -X PATCH repos/o/r/git/refs/heads/main -f sha=abc",
        ):
            with self.subTest(command=command):
                self.assert_blocked(command, "main")

    def test_forced_and_unverified_push_is_blocked(self):
        sh(["git", "checkout", "-q", "-b", "feat/x"], self.repo.root)
        for command, fragment in (
            ("git push --force origin feat/x", "--force"),
            ("git push origin feat/x --force-with-lease", "--force"),
            ("git push -f", "강제"),
            ("git push -uf origin feat/x", "강제"),
            ("git push origin +feat/x", "강제"),
            ("git push --all", "--all"),
            ("git push --mirror", "--mirror"),
            ("git push --no-verify origin feat/x", "--no-verify"),
            ("git commit --no-verify -m x", "--no-verify"),
            ("git commit -n -F m.txt", "commit -n"),
            ("git commit -nm x", "commit -n"),
            ("git commit -an -m x", "commit -n"),
            ("git -c core.hooksPath=/dev/null push origin feat/x", "core.hooksPath"),
        ):
            with self.subTest(command=command):
                self.assert_blocked(command, fragment)

    def test_push_without_refspec_is_blocked_on_main(self):
        self.assert_blocked("git push", "현재 브랜치가 main")

    def test_destructive_git_is_blocked(self):
        self.assert_blocked("git reset --hard HEAD~1", "reset --hard")
        self.assert_blocked("git clean -fd", "clean")
        self.assert_blocked("git stash", "stash")
        self.assert_blocked("git checkout -- src/app.py", "checkout")
        self.assert_blocked("git restore .", "restore")

    def test_read_only_git_is_allowed(self):
        for command in ("git status", "git diff --stat", "git log --oneline -3", "git stash list", "git commit-tree --help"):
            with self.subTest(command=command):
                self.assert_allowed(command)


class PreToolUseEditTest(unittest.TestCase):
    def setUp(self):
        self.repo = Repo()
        self.addCleanup(self.repo.cleanup)
        self.repo.add_task("1-demo", [
            {"phase": 0, "name": "docs", "scope": ["docs/", "README.md"]},
            {"phase": 1, "name": "impl", "scope": ["src/app/"]},
            {"phase": 2, "name": "domain", "scope": ["src/domain/"], "unfreeze": ["src/domain/"]},
        ])

    def run_edit(self, path: str, env: dict | None = None) -> subprocess.CompletedProcess:
        return run_hook("pre_tool_use.py", self.repo, edit(path, self.repo), env)

    def test_frozen_path_is_blocked_in_supervised_session(self):
        result = self.run_edit("src/domain/rule.txt")
        self.assertEqual(result.returncode, 2)
        self.assertIn("동결", result.stderr)

    def test_frozen_path_is_allowed_when_phase_unfreezes_it(self):
        result = self.run_edit("src/domain/rule.txt", {**RUNNER_ENV, "HARNESS_PHASE": "2"})
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_outside_scope_is_blocked_only_in_runner_session(self):
        blocked = self.run_edit(str(self.repo.root / "docs" / "guide.md"), RUNNER_ENV)
        self.assertEqual(blocked.returncode, 2)
        self.assertIn("src/app/", blocked.stderr)
        allowed = self.run_edit("docs/guide.md")
        self.assertEqual(allowed.returncode, 0, allowed.stderr)

    def test_inside_scope_is_allowed_in_runner_session(self):
        result = self.run_edit("src/app/new.py", RUNNER_ENV)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_path_outside_repo_is_blocked_only_in_runner_session(self):
        outside = str(self.repo.dir / "elsewhere.md")
        self.assertEqual(self.run_edit(outside, RUNNER_ENV).returncode, 2)
        self.assertEqual(self.run_edit(outside).returncode, 0)

    def test_notebook_path_is_checked_too(self):
        payload = {"tool_name": "NotebookEdit", "cwd": str(self.repo.root), "tool_input": {"notebook_path": "src/domain/a.ipynb"}}
        self.assertEqual(run_hook("pre_tool_use.py", self.repo, payload).returncode, 2)

    def test_config_error_does_not_block(self):
        self.repo.write("harness.json", "{not json")
        result = self.run_edit("docs/guide.md")
        self.assertEqual(result.returncode, 1)
        self.assertIn("차단하지 않음", result.stderr)


class StopHookTest(unittest.TestCase):
    def setUp(self):
        self.repo = Repo()
        self.addCleanup(self.repo.cleanup)
        self.repo.add_task("1-demo", [{"phase": 1, "name": "impl", "scope": ["src/app/"]}])
        self.baseline = sh(["git", "rev-parse", "HEAD"], self.repo.root).stdout.strip()

    def run_stop(self, env: dict | None = None, active: bool = False) -> subprocess.CompletedProcess:
        payload = {"hook_event_name": "Stop", "cwd": str(self.repo.root), "stop_hook_active": active}
        return run_hook("stop.py", self.repo, payload, env)

    def test_supervised_session_is_untouched(self):
        self.repo.write("docs/extra.md", "x\n")
        self.assertEqual(self.run_stop().returncode, 0)

    def test_runner_session_cannot_stop_with_out_of_scope_changes(self):
        self.repo.write("docs/extra.md", "x\n")
        result = self.run_stop({**RUNNER_ENV, "HARNESS_BASELINE": self.baseline})
        self.assertEqual(result.returncode, 2)
        self.assertIn("docs/extra.md", result.stderr)

    def test_runner_session_cannot_stop_with_frozen_changes(self):
        self.repo.write("src/domain/rule.txt", "changed\n")
        self.repo.write("src/app/ok.py", "ok\n")
        task = self.repo.root / "tasks" / "1-demo" / "index.json"
        index = json.loads(task.read_text())
        index["phases"][0]["scope"] = ["src/"]
        task.write_text(json.dumps(index))
        result = self.run_stop({**RUNNER_ENV, "HARNESS_BASELINE": self.baseline})
        self.assertEqual(result.returncode, 2)
        self.assertIn("동결", result.stderr)

    def test_runner_session_stops_when_clean_or_already_blocked_once(self):
        self.repo.write("src/app/ok.py", "ok\n")
        self.assertEqual(self.run_stop({**RUNNER_ENV, "HARNESS_BASELINE": self.baseline}).returncode, 0)
        self.repo.write("docs/extra.md", "x\n")
        self.assertEqual(self.run_stop({**RUNNER_ENV, "HARNESS_BASELINE": self.baseline}, active=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
