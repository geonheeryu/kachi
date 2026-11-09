#!/usr/bin/env python3
"""PreToolUse 훅. 저장소 규약을 도구 호출 시점에 차단한다.

stdin으로 Claude Code 훅 JSON(tool_name, tool_input, cwd)을 받는다.
위반이면 사유를 stderr에 쓰고 exit 2(차단, 권한 모드와 무관). 훅 자체의 설정 오류는 exit 1(비차단, 메시지만 표시).

항상 차단:
  - DESTRUCTIVE_GIT의 명령                    미커밋 작업을 지우는 명령
  - harness.json frozen_paths 안의 파일 편집   러너 세션이면 phase의 unfreeze는 허용
러너 세션(HARNESS_HEADLESS=1)에서만 차단:
  - git commit, git push                     세션은 커밋하지 않고 러너가 커밋한다
  - phase scope 밖 파일 편집(저장소 밖 포함)
감독 세션의 push는 훅이 아니라 .claude/settings.json의 permissions.ask가 사용자에게 묻는다(AGENTS.md).
Bash의 리다이렉트·sed -i 쓰기는 보지 않는다. 그 백스톱은 러너의 사후 검사다.
"""

from __future__ import annotations

import json
import os
import re
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


def project_root(payload: dict) -> Path:
    configured = os.environ.get("CLAUDE_PROJECT_DIR")
    if configured:
        return Path(configured).resolve()
    return find_project_root(Path(payload.get("cwd") or Path.cwd()))


def is_runner_session() -> bool:
    return os.environ.get("HARNESS_HEADLESS") == "1"


def check_bash(command: str) -> str | None:
    rules = DESTRUCTIVE_GIT + (RUNNER_ONLY_GIT if is_runner_session() else [])
    for pattern, reason in rules:
        if pattern.search(command):
            return reason
    return None


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
            reason = check_bash((payload.get("tool_input") or {}).get("command", ""))
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
