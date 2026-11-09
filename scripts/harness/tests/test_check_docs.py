from __future__ import annotations

import json
import unittest

from helpers import Repo, git

import check_docs


class GlobTests(unittest.TestCase):
    def test_single_star_does_not_cross_slash(self):
        self.assertTrue(check_docs.glob_to_regex("docs/*.md").match("docs/a.md"))
        self.assertFalse(check_docs.glob_to_regex("docs/*.md").match("docs/sub/a.md"))

    def test_double_star_crosses_slash(self):
        self.assertTrue(check_docs.glob_to_regex("docs/**/*.md").match("docs/a.md"))
        self.assertTrue(check_docs.glob_to_regex("docs/**/*.md").match("docs/x/y/a.md"))

    def test_exact_path(self):
        self.assertTrue(check_docs.glob_to_regex("README.md").match("README.md"))
        self.assertFalse(check_docs.glob_to_regex("README.md").match("docs/README.md"))

    def test_line_count(self):
        self.assertEqual(check_docs.line_count(""), 0)
        self.assertEqual(check_docs.line_count("a\nb\n"), 2)
        self.assertEqual(check_docs.line_count("a\nb"), 2)


class ProseTests(unittest.TestCase):
    def test_inline_example_in_bullet_fails(self):
        errors = check_docs.prose_line_errors("d.md", "- 규칙이다. 예: `a/b/`.\n")
        self.assertEqual(len(errors), 1)
        self.assertIn("d.md:1 인라인 `예:`", errors[0])

    def test_example_sub_bullet_passes(self):
        text = "- 규칙이다.\n  - 예: `a/b/`\n"
        self.assertEqual(check_docs.prose_line_errors("d.md", text), [])

    def test_example_inside_code_block_and_table_is_ignored(self):
        text = "```text\n규칙. 예: x\n```\n| 열 | 설명 |\n| --- | --- |\n| a | 값. 예: `x` |\n"
        self.assertEqual(check_docs.prose_line_errors("d.md", text), [])

    def test_table_cell_with_three_sentences_fails(self):
        text = "| 용어 | 설명 |\n| --- | --- |\n| a | 정의다. 구별이다. 흐름이다. |\n"
        errors = check_docs.prose_line_errors("d.md", text)
        self.assertEqual(len(errors), 1)
        self.assertIn("d.md:3 표 셀에 문장이 셋 이상", errors[0])

    def test_table_cell_with_two_sentences_and_code_periods_passes(self):
        text = "| 용어 | 설명 |\n| --- | --- |\n| a | 정의(`x.y.z`)다. 버전 1.0은 구별이다. |\n"
        self.assertEqual(check_docs.prose_line_errors("d.md", text), [])


class CliTests(unittest.TestCase):
    def setUp(self):
        self.repo = Repo()

    def tearDown(self):
        self.repo.cleanup()

    def test_ok_fixture_passes(self):
        result = self.repo.run_script("check_docs.py")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_tracked_doc_outside_allowlist_fails(self):
        self.repo.write("notes/stray.md", "x\n")
        self.repo.commit("stray")
        result = self.repo.run_script("check_docs.py")
        self.assertEqual(result.returncode, 1)
        self.assertIn("notes/stray.md", result.stderr)

    def test_line_limit_fails(self):
        self.repo.write("AGENTS.md", "1\n2\n3\n4\n")
        self.repo.commit("agents")
        result = self.repo.run_script("check_docs.py")
        self.assertEqual(result.returncode, 1)
        self.assertIn("AGENTS.md 4줄 (상한 3)", result.stderr)

    def test_staged_new_doc_not_in_head_allowlist_fails(self):
        self.repo.write("notes/new.md", "x\n")
        git("add", "notes/new.md", cwd=self.repo.root)
        result = self.repo.run_script("check_docs.py", "--staged")
        self.assertEqual(result.returncode, 1)
        self.assertIn("HEAD 허용 목록", result.stderr)

    def test_staged_new_doc_matching_glob_passes(self):
        self.repo.write("docs/adr/0001-x.md", "x\n")
        git("add", "docs/adr/0001-x.md", cwd=self.repo.root)
        result = self.repo.run_script("check_docs.py", "--staged")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_widening_allowlist_in_same_commit_still_fails(self):
        cfg = json.loads((self.repo.root / "scripts/doc-paths.json").read_text())
        cfg["allowed"].append("notes/*.md")
        self.repo.write("scripts/doc-paths.json", json.dumps(cfg))
        self.repo.write("notes/new.md", "x\n")
        git("add", "-A", cwd=self.repo.root)
        result = self.repo.run_script("check_docs.py", "--staged")
        self.assertEqual(result.returncode, 1)

    def test_prose_exempt_glob_skips_file(self):
        cfg = json.loads((self.repo.root / "scripts/doc-paths.json").read_text())
        cfg["proseExempt"] = ["docs/adr/*.md"]
        self.repo.write("scripts/doc-paths.json", json.dumps(cfg))
        self.repo.write("docs/adr/0001-x.md", "- 결정이다. 예: `x`.\n")
        self.repo.write("docs/guide.md", "- 규칙이다. 예: `x`.\n")
        self.repo.commit("prose")
        result = self.repo.run_script("check_docs.py")
        self.assertEqual(result.returncode, 1)
        self.assertIn("docs/guide.md:1", result.stderr)
        self.assertNotIn("docs/adr/0001-x.md", result.stderr)

    def test_staged_prose_violation_fails(self):
        self.repo.write("docs/guide.md", "| a | b |\n| --- | --- |\n| x | 하나. 둘. 셋. |\n")
        git("add", "docs/guide.md", cwd=self.repo.root)
        result = self.repo.run_script("check_docs.py", "--staged")
        self.assertEqual(result.returncode, 1)
        self.assertIn("docs/guide.md:3 표 셀", result.stderr)

    def test_missing_config_exit_2(self):
        (self.repo.root / "scripts/doc-paths.json").unlink()
        result = self.repo.run_script("check_docs.py")
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
