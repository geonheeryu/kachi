# story 골드셋과 임계값 측정

story-service 조립 규칙(`DESIGN-U.md` 7절)의 세 임계값 θ_high·θ_low·θ_judge를 정하기 위한 실험이다.
실제 수집 기사로 "같은 사건인가"를 사람이 라벨링한 쌍 250개에 bge-m3 코사인 유사도와 bge-reranker-v2-m3 판정 점수를 내고,
조합마다 정밀도·재현율을 재서 정밀도 우선으로 값을 고른다(`DESIGN-U.md` 11절).

## 입력

로컬 Mongo의 두 컬렉션이다. `scripts/run.sh extract`가 `docker exec kachi-mongo mongosh`로 `generated/`에 뽑는다.

- `kachi_ai.news_summaries`: 어떤 기사들이 한 요약에 묶였는지, 어떤 기사가 여러 요약에 인용됐는지
- `kachi_collector.news`: 제목, 발췌문, 출처, 언어, 수집 시각

2026-09-08 이관으로 옛 기사 2,622건은 `excerpt`가 제목 복사본이다. 그 경우 발췌문이 없는 것으로 보고 임베딩 입력을 제목만으로 만든다.
요약에 인용된 기사는 전부 이쪽이라 S1~S4는 제목만 있는 입력이고, 발췌문이 있는 입력은 S5로만 본다.

## 쌍 샘플링 (`ExtractPairs`)

| 층 | 뽑는 법 | 수 |
| --- | --- | --- |
| S1 | 한 요약에 같이 인용된 기사 둘 | 80 |
| S2 | 두 요약 이상에 인용된 기사와, 그중 한 요약에만 있는 다른 기사 | 50 |
| S3 | 같은 키워드, 인용이 겹치지 않고 6시간 이상 떨어진 두 요약에서 하나씩 | 60 |
| S4 | 키워드가 다른 두 요약에서 하나씩 | 30 |
| S5 | 발췌문이 있는 최근 수집분. 매체 접미·태그를 뗀 제목 토큰이 겹치되 같지는 않은 상위 15 + 무작위 15 | 30 |

층은 샘플링 기준이지 라벨이 아니다. 시드 고정(`20260908`)이라 같은 export에서 같은 쌍이 나온다.

## 라벨 (`DraftLabels`)

로컬 Ollama `qwen3.8:27b`가 쌍마다 `{same, reason}` 초안을 낸다. 정답은 사람이 `results/review.md`를 보고 `goldset/pairs.jsonl`의 `label`에 채운다.
초안만 있는 상태로는 임계값을 확정하지 않는다.

## 점수 (`ScorePairs`)

DJL PyTorch 엔진으로 두 모델을 돈다. 가중치는 TEI가 쓰는 것과 같고 이 Mac(arm64)에서 네이티브로 실행된다.

| 역할 | 모델 | 입력 | 출력 |
| --- | --- | --- | --- |
| 임베딩 | `BAAI/bge-m3` (cls pooling, L2 정규화) | `title\nexcerpt` | 코사인 유사도 |
| 판정기 | `BAAI/bge-reranker-v2-m3` (cross-encoder, sigmoid) | (A 텍스트, B 텍스트) | 0~1 |

모델은 `mlrepo.djl.ai`의 TorchScript 변환본을 zip 주소로 받는다(첫 실행 약 3.6GB, `~/.djl.ai/`).
`--embedding e5`로 `intfloat/multilingual-e5-large`(mean pooling, `query: ` 접두어)도 낼 수 있다.

## 평가 (`EvaluateThresholds`)

조립 규칙 ③을 그대로 함수로 두고 θ 조합을 전수로 돌린다.

```
cos ≥ θ_high            -> 같은 story
θ_low ≤ cos < θ_high    -> 판정기 ≥ θ_judge 면 같은 story
cos < θ_low             -> 새 story
```

θ_high·θ_low ∈ {0.40 … 0.95, 0.05 간격}, θ_judge ∈ {0.30 … 0.95, 0.05 간격}.
정밀도 ≥ 0.95인 조합 중 재현율이 가장 높은 것을 고른다. 오탐(다른 사건을 합침)이 미탐(같은 사건을 나눔)보다 나쁘기 때문이다.
결과는 `results/thresholds.md`.

## 실행

```sh
./scripts/run.sh extract            # generated/ -> goldset/pairs.jsonl
./scripts/run.sh draft              # Ollama 초안, results/review.md
./scripts/run.sh score              # results/scores.jsonl
./scripts/run.sh review             # review.md를 코사인 순으로 다시 만든다
#   review.md의 확정 칸에 같음/다름을 적는다
./scripts/run.sh apply              # 확정 칸 -> goldset/pairs.jsonl의 label
./scripts/run.sh evaluate           # results/thresholds.md  (--use-draft: 초안 기준 참고용)
```

독립 Gradle 빌드라 루트 `./gradlew test`에 들어가지 않는다.

## 결과 (2026-09-08)

라벨은 Claude가 250쌍을 읽고 판정한 것을 qwen3.8:27b 초안과 대조해, 일치한 242쌍은 그대로 두고 갈린 8쌍은 사용자가 확정했다.
같은 사건 38쌍, 다른 사건 212쌍이다. 전체 표와 오답 목록은 `results/thresholds.md`.

| θ_high | θ_low | θ_judge | 정밀도 | 재현율 | 회색 구간 | 비고 |
| --- | --- | --- | --- | --- | --- | --- |
| 0.70 | 0.60 | 0.30 | 0.969 | 0.816 | 5% | 선택. 정밀도 ≥ 0.95 중 재현율 최대 |
| 0.70 | 0.60 | 0.60 | 1.000 | 0.763 | 5% | 오탐 0의 대안 |
| 코사인만 0.60 | | | 0.892 | 0.868 | | 판정기 없이 |

관찰.

- 판정기는 제목만 있는 입력에서 같은 사건을 거의 못 알아본다. 미탐 7건 중 6건이 판정기 0.07 이하다. bge-reranker는 질의와 문서의 관련성을 배운 모델이라 짧은 제목 둘의 대칭 비교에는 약하다.
  발췌문이 있는 쌍(44)에서는 재현율 1.0이라, 운영 입력(발췌문 포함)에서는 다르게 나올 수 있다.
- 판정기의 기여는 정밀도다. 코사인만 0.60으로 자르면 정밀도 0.892인데, 회색 구간(0.60~0.70)에 판정기를 두면 0.969다. 재현율은 조금 준다.
- 오탐 1건(S5-023)은 미국 해상봉쇄의 결과를 다룬 두 기사(이란 원유 수출 중단 vs 해운 연료비)다. 같은 원인의 다른 사건이며, 발췌문이 유사도를 올렸다.
- 같은 요약에 묶였던 쌍(S1) 80개 중 같은 사건은 12개다. 키워드 단위 요약이 사건을 섞고 있었다.
- 같은 기사의 Naver·Google 사본(제목의 매체 접미만 다름)은 코사인 0.63~0.98이다. 0.63(S5-009)은 발췌문이 한쪽만 있는 경우라, 발췌문 유무가 다른 사본은 자동 병합 구간에 못 들어갈 수 있다.

한계.

- 같은 사건 쌍이 38개라 재현율 차이 1건이 0.026이다. 값은 U2 ③의 설정 기본값이며, 운영 데이터가 쌓이면 발췌문 있는 쌍으로 다시 잰다.
- 206쌍이 제목만 있는 입력이다. 발췌문 있는 쌍은 30일 안의 수집분(S5)뿐이다.
