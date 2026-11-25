#!/usr/bin/env python3
"""PreToolUse 훅. 저장소 규약을 도구 호출 시점에 차단한다.

stdin으로 Claude Code 훅 JSON(tool_name, tool_input, cwd)을 받는다.
위반이면 사유를 stderr에 쓰고 exit 2(차단, 권한 모드와 무관). 훅 자체의 설정 오류는 exit 1(비차단, 메시지만 표시).

항상 차단:
  - DESTRUCTIVE_GIT의 명령                    미커밋 작업을 지우는 명령
  - check_human_only의 명령                   PR 병합, 강제 push, main으로의 push, --no-verify. 병합은 사람만 한다
  - harness.json frozen_paths 안의 파일 편집   러너 세션이면 phase의 unfreeze는 허용
러너 세션(HARNESS_HEADLESS=1)에서만 차단:
  - git commit, git push                     세션은 커밋하지 않고 러너가 커밋한다
  - phase scope 밖 파일 편집(저장소 밖 포함)
감독 세션은 기능 브랜치의 커밋·push·PR 생성을 pr-review 스킬의 흐름 안에서 승인 없이 한다(AGENTS.md).
Bash의 리다이렉트·sed -i 쓰기는 보지 않는다. 그 백스톱은 러너의 사후 검사다.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from _utils import HarnessError, find_project_root, load_config  # noqa: E402
from scope_check import current_phase, path_under, relative_to_root  # noqa: E402

EDIT_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}

# (정규식, 사유). 명령 문자열 전체를 대상으로 하므로 `cd x && git commit`도 잡는다.
RUNNER_ONLY_GIT = [
    (re.compile(r"\bgit\s+(?:-C\s+\S+\s+)?(?:\S+\s+)*?commit(?![\w-])"), "git commit: 러너 세션은 커밋하지 않습니다. 러너가 phase 끝에 커밋합니다"),
    (re.compile(r"\bgit\s+(?:-C\s+\S+\s+)?(?:\S+\s+)*?push(?![\w-])"), "git push: 러너 세션은 push하지 않습니다"),
]
DESTRUCTIVE_GIT = [
    (re.compile(r"\bgit\s+(?:-C\s+\S+\s+)?reset\s+(?:\S+\s+)*--hard\b"), "git reset --hard: 미커밋 작업을 지웁니다"),
    (re.compile(r"\bgit\s+(?:-C\s+\S+\s+)?clean\s+(?:\S+\s+)*-\S*[fdx]"), "git clean: 미추적 파일을 지웁니다"),
    (re.compile(r"\bgit\s+(?:-C\s+\S+\s+)?stash\b(?!\s+(?:list|show)\b)"), "git stash: 워킹트리를 비웁니다"),
    (re.compile(r"\bgit\s+(?:-C\s+\S+\s+)?checkout\s+(?:\S+\s+)*--\s"), "git checkout -- <path>: 미커밋 변경을 덮어씁니다"),
    (re.compile(r"\bgit\s+(?:-C\s+\S+\s+)?restore\b"), "git restore: 미커밋 변경을 덮어씁니다"),
]
# git과 하위 명령 사이에는 전역 옵션만 허용한다. 커밋 메시지 안의 단어 push를 명령으로 읽지 않기 위해서다.
GIT = r"\bgit(?:\s+(?:-[Cc]\s+\S+|--[\w-]+(?:=\S+)?))*\s+"
GIT_PUSH = re.compile(GIT + r"push(?![\w-])")
GIT_COMMIT = re.compile(GIT + r"commit(?![\w-])")
# 새 브랜치를 main에서 따는 꼴(-b, -c)은 main으로 옮겨 가는 것이 아니다.
GIT_TO_MAIN = re.compile(GIT + r"(?:checkout|switch)\s+(?!(?:\S+\s+)*?-[bBcC]\b)(?:\S+\s+)*?['\"]?main['\"]?\s*$")
TOKEN_EDGE = "'\"(){}`"
SEGMENT = re.compile(r"[;&|\n]+")
MAIN_REFS = {"main", "refs/heads/main"}
HUMAN_ONLY = [
    (re.compile(r"\bgh\b[^;&|\n]*\bpr\s+merge\b"), "gh pr merge: 병합은 사용자만 합니다"),
    (re.compile(r"\bpulls/[^/\s]+/merge\b"), "pulls/<번호>/merge API: 병합은 사용자만 합니다"),
    (re.compile(r"\b(?:mergePullRequest|enablePullRequestAutoMerge)\b"), "병합 GraphQL mutation: 병합은 사용자만 합니다"),
    (re.compile(r"\brepos/[^/\s]+/[^/\s]+/merges\b"), "merges API: 병합은 사용자만 합니다"),
    (re.compile(r"\bgit/refs/heads/main\b"), "git/refs/heads/main API: main에는 직접 쓰지 않습니다"),
    (re.compile(r"\bcore\.hooksPath\b"), "core.hooksPath: pre-commit과 pre-push 검사를 건너뜁니다"),
]


def project_root(payload: dict) -> Path:
    configured = os.environ.get("CLAUDE_PROJECT_DIR")
    if configured:
        return Path(configured).resolve()
    return find_project_root(Path(payload.get("cwd") or Path.cwd()))


def is_runner_session() -> bool:
    return os.environ.get("HARNESS_HEADLESS") == "1"


def current_branch(cwd: Path) -> str:
    result = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=str(cwd), capture_output=True, text=True)
    return result.stdout.strip()


def check_push(args: str) -> str | None:
    """push 명령 하나의 인자를 본다. 뒤에 이어진 다른 명령의 인자는 넘어오지 않는다."""
    for raw in args.split():
        token = re.split(r"[<>]", raw, maxsplit=1)[0].strip(TOKEN_EDGE)
        if token.startswith("--force") or token in ("--mirror", "--all"):
            return f"git push {token}: 원격 이력이나 다른 브랜치를 덮어씁니다"
        if token.startswith("+") or (re.fullmatch(r"-[a-zA-Z]+", token) and "f" in token):
            return "강제 push: 원격 이력을 덮어씁니다"
        if token in MAIN_REFS or token.rsplit(":", 1)[-1] in MAIN_REFS:
            return "main으로의 push: main에는 직접 push하지 않습니다"
    return None


def check_human_only(command: str, cwd: Path) -> str | None:
    for pattern, reason in HUMAN_ONLY:
        if pattern.search(command):
            return reason
    segments = [s.strip() for s in SEGMENT.split(command)]
    pushes = [m for m in (GIT_PUSH.search(s) for s in segments) if m]
    for segment in segments:
        commit = GIT_COMMIT.search(segment)
        if "--no-verify" in segment and (GIT_PUSH.search(segment) or commit):
            return "--no-verify: pre-commit과 pre-push 검사를 건너뜁니다"
        # commit의 -n은 --no-verify의 단축 꼴이다. 메시지(-m 뒤)는 보지 않는다.
        for token in segment[commit.end():].split() if commit else ():
            short = re.fullmatch(r"-[a-zA-Z]+", token)
            if short and "n" in token:
                return "git commit -n: --no-verify와 같습니다. pre-commit 검사를 건너뜁니다"
            if (short and re.search(r"[mF]", token)) or token.startswith(("--message", "--file")):
                break
    for match in pushes:
        reason = check_push(match.string[match.end():])
        if reason:
            return reason
    if pushes:
        # refspec 없는 push는 현재 브랜치를 올린다. 같은 명령 안에서 main으로 옮겨 간 경우도 막는다.
        if current_branch(cwd) == "main" or any(GIT_TO_MAIN.search(s) for s in segments):
            return "현재 브랜치가 main입니다. main에는 직접 push하지 않습니다"
    return None


def check_bash(command: str, cwd: Path) -> str | None:
    rules = DESTRUCTIVE_GIT + (RUNNER_ONLY_GIT if is_runner_session() else [])
    for pattern, reason in rules:
        if pattern.search(command):
            return reason
    return check_human_only(command, cwd)


def check_edit(root: Path, payload: dict) -> str | None:
    tool_input = payload.get("tool_input") or {}
    raw = tool_input.get("file_path") or tool_input.get("notebook_path")
    if not raw:
        return None
    phase = current_phase(root)
    rel = relative_to_root(root, raw, Path(payload.get("cwd") or root))

    if rel is not None:
        frozen = [p for p in load_config(root).get("frozen_paths", []) if phase is None or p not in phase.unfreeze]
        if path_under(rel, frozen):
            return f"동결 경로입니다: {rel}. harness.json frozen_paths에 있고 이 phase가 unfreeze하지 않았습니다"

    if phase is not None:
        if rel is None:
            return f"저장소 밖 경로입니다: {raw}. phase {phase.id}의 scope는 {phase.scope}입니다"
        if not path_under(rel, phase.scope):
            return f"phase {phase.id} scope 밖 경로입니다: {rel}. 허용 scope: {phase.scope}. 필요하면 phase 파일과 index.json의 scope를 먼저 고쳐야 합니다"
    return None


def main() -> None:
    try:
        payload = json.loads(sys.stdin.read() or "{}")
    except json.JSONDecodeError:
        sys.exit(0)
    tool = payload.get("tool_name", "")
    try:
        if tool == "Bash":
            reason = check_bash((payload.get("tool_input") or {}).get("command", ""), Path(payload.get("cwd") or Path.cwd()))
        elif tool in EDIT_TOOLS:
            reason = check_edit(project_root(payload), payload)
        else:
            reason = None
    except HarnessError as exc:
        print(f"pre_tool_use 훅 설정 오류(차단하지 않음): {exc}", file=sys.stderr)
        sys.exit(1)
    if reason:
        print(f"훅이 차단했습니다. {reason}", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
