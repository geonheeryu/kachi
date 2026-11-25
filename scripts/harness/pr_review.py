#!/usr/bin/env python3
"""PR 검토 봇(2단)의 입력을 모으고, 봇이 낸 구조화 결과를 검증해 코멘트로 쓴다.

봇은 코멘트 도구와 셸 없이 파일만 읽고 JSON을 낸다. 회차, 지적 번호, 판정, 게시 범위는
봇이 아니라 이 스크립트가 정한다. 규칙의 원본은 .agents/pr-review-common.md다.

  collect  PR 정보, 작성자 코멘트, 이전 지적, diff를 --out 디렉터리에 쓴다.
           이전 회차가 검토한 커밋과 head가 같고 그 뒤 작성자 코멘트가 없으면 GITHUB_OUTPUT에 skip=true를 쓴다.
  post     환경 변수 PR_REVIEW_RESULT(봇의 JSON)를 검증해 인라인 코멘트와 요약 1개를 쓴다.

Usage: python3 scripts/harness/pr_review.py collect --repo O/R --pr N --head SHA --out DIR
       python3 scripts/harness/pr_review.py post --repo O/R --pr N --head SHA
exit 0 완료, 1 봇 결과가 JSON이 아니거나 형식이 다름
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

MARKER = "<!-- pr-review-bot -->"
STATE_OPEN = "<!-- pr-review-state"
BOT_LOGIN = "github-actions[bot]"
GRADES = ("P0", "P1", "P2")
OPEN, RESOLVED, EXPLAINED = "남음", "해결됨", "소명됨"
STATUSES = (OPEN, RESOLVED, EXPLAINED)
MAX_ROUND = 2
INLINE_LIMIT = 10
NEW_LIMIT = 30
TEXT_LIMIT = 1200
PROTECTED_PATHS = (
    ".github/workflows/pr-review.yml",
    ".github/workflows/ci.yml",
    "harness.json",
    ".agents/pr-review-common.md",
    ".agents/pr-review-exclusions.md",
    ".agents/code-review-guideline.md",
    ".agents/skills/pr-review/SKILL.md",
    ".claude/agents/pr-guideline-reviewer.md",
    ".claude/agents/pr-defect-reviewer.md",
    ".claude/settings.json",
    "scripts/harness/pr_review.py",
    "scripts/harness/hooks/pre_tool_use.py",
    "scripts/harness/hooks/stop.py",
    "scripts/harness/githooks/pre-commit",
    "scripts/harness/githooks/pre-push",
)
SECRET_PATTERNS = (
    re.compile(r"sk-ant-[A-Za-z0-9_\-]{8,}"),
    re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
)


def redact(text: str) -> str:
    for pattern in SECRET_PATTERNS:
        text = pattern.sub("[가림]", text)
    return text


def clean(text: object, limit: int = TEXT_LIMIT) -> str:
    one_line = " ".join(str(text).split())
    # 봇이 낸 글이 요약의 숨은 상태 주석이나 HTML로 읽히지 않게 한다
    return redact(one_line)[:limit].replace("<", "&lt;").replace(">", "&gt;")


def patch_paths(patch: str) -> set[str]:
    """diff가 건드린 경로. 이름을 바꾼 파일은 옛 경로와 새 경로가 모두 든다."""
    paths: set[str] = set()
    for m in re.finditer(r"^diff --git a/(.+?) b/(.+)$", patch, re.M):
        paths.update(m.groups())
    return paths


def filter_patch(patch: str, paths: set[str]) -> str:
    """paths에 있는 파일의 절만 남긴다. rebase로 끼어든 다른 PR의 변경을 뺀다."""
    sections = re.split(r"(?m)^(?=diff --git )", patch)
    kept = []
    for section in sections:
        m = re.match(r"diff --git a/(.+?) b/(.+)\n", section)
        if m and (m.group(1) in paths or m.group(2) in paths):
            kept.append(section)
    return "".join(kept)


def parse_state(body: str) -> dict | None:
    start = body.rfind(STATE_OPEN)
    if start < 0:
        return None
    end = body.find("-->", start)
    if end < 0:
        return None
    try:
        state = json.loads(body[start + len(STATE_OPEN):end])
    except json.JSONDecodeError:
        return None
    return state if isinstance(state, dict) else None


def find_previous_summary(comments: list[dict]) -> dict | None:
    """검토 봇 계정이 쓴 마지막 요약. 다른 계정이 marker를 흉내 낸 코멘트는 무시한다."""
    for comment in reversed(comments):
        login = (comment.get("user") or {}).get("login")
        if login == BOT_LOGIN and (comment.get("body") or "").startswith(MARKER):
            return comment
    return None


def next_round(state: dict | None) -> int:
    return int(state["round"]) + 1 if state else 1


def build_review(result: dict, state: dict | None, head: str, pr_paths: set[str]) -> dict:
    """봇 결과와 이전 상태로 이번 회차의 지적 목록, 판정, 게시 대상을 정한다."""
    round_no = next_round(state)
    previous = [dict(f) for f in (state or {}).get("findings", [])]
    updates = {u.get("id"): u for u in (result.get("previous") or []) if isinstance(u, dict)}
    for finding in previous:
        update = updates.get(finding["id"])
        if finding["status"] == RESOLVED or not update or update.get("status") not in STATUSES:
            continue
        status, note = update["status"], clean(update.get("note", ""), 400)
        if finding["grade"] == "P0" and status == EXPLAINED:
            status, note = OPEN, "P0은 소명으로 내려가지 않는다"
        finding["status"], finding["note"] = status, note

    new, p2_count, dropped = [], 0, 0
    raw = [f for f in (result.get("findings") or []) if isinstance(f, dict) and f.get("grade") in GRADES]
    raw.sort(key=lambda f: GRADES.index(f["grade"]))
    for item in raw:
        if item["grade"] == "P2":
            p2_count += 1
            continue
        if len(new) >= NEW_LIMIT:
            dropped += 1
            continue
        path = str(item.get("path", ""))
        line = item.get("line") if isinstance(item.get("line"), int) else 0
        new.append({
            "id": f"R{round_no}-{len(new) + 1}",
            "grade": item["grade"],
            "path": clean(path, 300),
            "line": line,
            "text": clean(item.get("text", "")),
            "status": OPEN,
            "note": "",
            "inline": path in pr_paths and line > 0,
        })
    for index, finding in enumerate([f for f in new if f["inline"]]):
        finding["inline"] = index < INLINE_LIMIT

    questions = [clean(q) for q in (result.get("questions") or []) if str(q).strip()][:NEW_LIMIT]
    touched = sorted(pr_paths & set(PROTECTED_PATHS))
    if touched:
        questions.insert(0, "이 PR이 리뷰 기준이나 차단 장치를 바꾼다. 사람이 직접 읽어야 한다: " + ", ".join(touched))
    everything = previous + new
    still_open = [f for f in everything if f["status"] == OPEN]
    p0 = sum(1 for f in still_open if f["grade"] == "P0")
    p1 = sum(1 for f in still_open if f["grade"] == "P1")
    verdict = "차단" if p0 else "수정 필요" if p1 else "통과"
    return {
        "round": round_no,
        "head": head,
        "verdict": verdict,
        "p0": p0,
        "p1": p1,
        "p2": p2_count,
        "dropped": dropped,
        "previous": previous,
        "new": new,
        "questions": questions,
        "reviewed": clean(result.get("reviewed", "")),
        "not_reviewed": clean(result.get("not_reviewed", "")),
    }


def finding_line(finding: dict) -> str:
    where = f"`{finding['path']}:{finding['line']}`" if finding["line"] else f"`{finding['path']}`"
    return f"- [{finding['grade']}] {finding['id']} {where} {finding['text']}"


def render_summary(review: dict) -> str:
    previous, new = review["previous"], review["new"]
    explained = [f for f in previous if f["status"] == EXPLAINED]
    counts = {s: sum(1 for f in previous if f["status"] == s) for s in STATUSES}
    lines = [
        MARKER,
        f"판정: {review['verdict']} · 회차 {review['round']} · 검토 커밋 {review['head'][:7]}"
        f" · P0 {review['p0']} · P1 {review['p1']} · P2 {review['p2']}"
        f" · 질문 {len(review['questions'])} · 소명됨 {len(explained)}",
        "",
        "이 판정은 봇의 판정이고 사람의 승인을 대신하지 않는다.",
    ]
    if review["round"] > MAX_ROUND and any(f["status"] == OPEN for f in previous):
        lines.append(f"회차가 {MAX_ROUND}를 넘었다. 이전 지적 가운데 남은 것은 더 왕복하지 않고 사람이 확인한다.")
    lines += ["", "**새 지적**"]
    lines += [finding_line(f) + ("" if f["inline"] else " (인라인 없음)") for f in new] or ["없음"]
    lines += ["", f"**이전 지적** 해결됨 {counts[RESOLVED]} · 소명됨 {counts[EXPLAINED]} · 남음 {counts[OPEN]}"]
    lines += [f"{finding_line(f)} / {f['status']}" for f in previous if f["status"] != EXPLAINED] or ["없음"]
    lines += ["", "**소명됨**"]
    lines += [f"{finding_line(f)} / 소명: {f['note']}" for f in explained] or ["없음"]
    lines += ["", "**질문**"]
    lines += [f"- {q}" for q in review["questions"]] or ["없음"]
    lines += ["", f"본 범위: {review['reviewed'] or '적지 않음'}", f"못 본 범위: {review['not_reviewed'] or '적지 않음'}"]
    if review["p2"]:
        lines.append(f"P2 {review['p2']}건은 게시하지 않았다.")
    if review["dropped"]:
        lines.append(f"새 지적이 {NEW_LIMIT}건을 넘어 {review['dropped']}건은 게시하지 않았다.")
    state = {
        "round": review["round"],
        "sha": review["head"],
        "findings": [{k: f[k] for k in ("id", "grade", "path", "line", "text", "status", "note")} for f in previous + new],
    }
    encoded = json.dumps(state, ensure_ascii=False).replace("<", "\\u003c").replace(">", "\\u003e")
    lines += ["", f"{STATE_OPEN} {encoded} -->"]
    return "\n".join(lines) + "\n"


def gh(*args: str, stdin: str | None = None, check: bool = True) -> str:
    done = subprocess.run(["gh", "api", *args], input=stdin, capture_output=True, text=True)
    if check and done.returncode != 0:
        raise RuntimeError(f"gh api {args[0]} 실패: {done.stderr.strip()}")
    return done.stdout if done.returncode == 0 else ""


def issue_comments(repo: str, pr: int) -> list[dict]:
    out = gh(f"repos/{repo}/issues/{pr}/comments", "--paginate", "--jq", ".[] | @json")
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def pr_diff(repo: str, pr: int) -> str:
    return gh(f"repos/{repo}/pulls/{pr}", "-H", "Accept: application/vnd.github.diff")


def set_output(name: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(f"{name}={value}\n")


def is_stale(info: dict, head: str) -> bool:
    """워크플로 재실행은 원래 이벤트의 커밋으로 돈다. 그 사이 head가 바뀌었으면 옛 커밋이다."""
    return (info.get("head") or {}).get("sha") != head


def collect(repo: str, pr: int, head: str, out: Path) -> int:
    info = json.loads(gh(f"repos/{repo}/pulls/{pr}"))
    if is_stale(info, head):
        set_output("skip", "true")
        print("이 실행의 커밋이 PR의 현재 head가 아니다. 옛 커밋은 검토하지 않는다.")
        return 0
    author = info["user"]["login"]
    comments = issue_comments(repo, pr)
    summary = find_previous_summary(comments)
    state = parse_state(summary["body"]) if summary else None
    replied = summary and any(
        (c.get("user") or {}).get("login") == author and c.get("created_at", "") > summary.get("updated_at", "")
        for c in comments
    )
    if state and state.get("sha") == head and not replied:
        set_output("skip", "true")
        print("이전 회차가 이 커밋을 이미 검토했고 그 뒤 작성자 코멘트가 없다. 건너뛴다.")
        return 0
    set_output("skip", "false")

    # PR이 같은 이름의 디렉터리를 커밋해 두었을 수 있다. 입력은 이 스크립트가 쓴 것만 남긴다.
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    (out / "pr.md").write_text(
        f"작성자: {author}\n회차: {next_round(state)}\n제목: {info.get('title', '')}\n\n{info.get('body') or ''}\n",
        encoding="utf-8",
    )
    mine = [c for c in comments if (c.get("user") or {}).get("login") == author]
    (out / "author-comments.md").write_text(
        "\n\n".join(f"## {c['created_at']}\n\n{c.get('body') or ''}" for c in mine) or "없음\n", encoding="utf-8"
    )
    carried = [f for f in (state or {}).get("findings", []) if f.get("status") != RESOLVED]
    (out / "previous.json").write_text(json.dumps(carried, ensure_ascii=False, indent=2), encoding="utf-8")
    full = pr_diff(repo, pr)
    (out / "diff.patch").write_text(full, encoding="utf-8")
    if state and state.get("sha"):
        since = gh(
            f"repos/{repo}/compare/{state['sha']}...{head}", "-H", "Accept: application/vnd.github.diff", check=False
        )
        since = filter_patch(since, patch_paths(full))
        if since:
            (out / "diff-since-last.patch").write_text(since, encoding="utf-8")
    return 0


def post(repo: str, pr: int, head: str) -> int:
    try:
        result = json.loads(os.environ.get("PR_REVIEW_RESULT", ""))
    except json.JSONDecodeError:
        print("봇 결과가 JSON이 아니다.", file=sys.stderr)
        return 1
    if not isinstance(result, dict):
        print("봇 결과가 객체가 아니다.", file=sys.stderr)
        return 1
    comments = issue_comments(repo, pr)
    summary = find_previous_summary(comments)
    state = parse_state(summary["body"]) if summary else None
    review = build_review(result, state, head, patch_paths(pr_diff(repo, pr)))

    for finding in review["new"]:
        if not finding["inline"]:
            continue
        body = f"[{finding['grade']}] {finding['id']} {finding['text']}"
        payload = json.dumps(
            {"body": body, "commit_id": head, "path": finding["path"], "line": finding["line"], "side": "RIGHT"}
        )
        if not gh(f"repos/{repo}/pulls/{pr}/comments", "-X", "POST", "--input", "-", stdin=payload, check=False):
            finding["inline"] = False

    payload = json.dumps({"body": render_summary(review)})
    if summary:
        gh(f"repos/{repo}/issues/comments/{summary['id']}", "-X", "PATCH", "--input", "-", stdin=payload)
    else:
        gh(f"repos/{repo}/issues/{pr}/comments", "-X", "POST", "--input", "-", stdin=payload)
    print(f"판정 {review['verdict']}, 회차 {review['round']}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=("collect", "post"))
    parser.add_argument("--repo", required=True)
    parser.add_argument("--pr", required=True, type=int)
    parser.add_argument("--head", required=True)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)
    if args.command == "collect":
        if not args.out:
            parser.error("collect에는 --out이 필요하다")
        return collect(args.repo, args.pr, args.head, args.out)
    return post(args.repo, args.pr, args.head)


if __name__ == "__main__":
    sys.exit(main())
