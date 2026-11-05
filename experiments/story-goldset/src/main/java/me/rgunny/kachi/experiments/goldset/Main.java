package me.rgunny.kachi.experiments.goldset;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 골드셋 실험의 진입점. 네 단계를 순서대로 따로 돌린다.
 *
 * <pre>
 * extract   generated/*.jsonl -> goldset/pairs.jsonl (쌍 250개)
 * draft     Ollama로 초안 -> pairs.jsonl 갱신, results/review.md
 * review    검토표만 다시 만든다 (점수가 있으면 코사인 순)
 * apply     검토표의 확정 칸 -> pairs.jsonl의 label
 * score     DJL로 코사인·판정 점수 -> results/scores.jsonl
 * evaluate  θ 조합 평가 -> results/thresholds.md
 * </pre>
 * 작업 디렉터리는 실험 루트(experiments/story-goldset)여야 한다. Gradle run이 그렇게 맞춘다.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: extract [--force] | draft | review | apply | score [--embedding bge|e5] | evaluate [--use-draft] [--embedding bge|e5]");
            System.exit(2);
        }
        List<String> options = Arrays.asList(args).subList(1, args.length);
        Path dir = Path.of("").toAbsolutePath();
        String embedding = optionValue(options, "--embedding", "bge");

        switch (args[0]) {
            case "extract" -> ExtractPairs.run(dir, options.contains("--force"));
            case "draft" -> DraftLabels.run(dir);
            case "review" -> ReviewTable.write(dir);
            case "apply" -> ReviewTable.apply(dir);
            case "score" -> ScorePairs.run(dir, embedding);
            case "evaluate" -> EvaluateThresholds.run(dir, options.contains("--use-draft"), embedding);
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static String optionValue(List<String> options, String name, String defaultValue) {
        int at = options.indexOf(name);
        if (at < 0 || at + 1 >= options.size()) {
            return defaultValue;
        }
        return options.get(at + 1);
    }
}
