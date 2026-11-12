# 011. ai-service newsHash 기반 뉴스 요약 중복 방지

## 배경

`ai-service`는 `collector-service`가 저장한 뉴스를 키워드 단위로 읽고 LLM으로 요약한다.
같은 요약 작업은 scheduler 재실행, 수동 재시도, 장애 복구 과정에서 반복될 수 있다.

여기서 중복 방지 대상은 뉴스 원문이 아니다.
중복 방지 대상은 "같은 입력 뉴스 묶음으로 만든 요약 결과"다.

예를 들어 다음 두 실행은 같은 요약 입력이다.

```text
keyword = NVIDIA
sourceNewsIds = [A, B, C]
```

```text
keyword = NVIDIA
sourceNewsIds = [C, B, A]
```

조회 순서는 다르지만 요약에 사용한 뉴스 id 묶음은 같다.
반대로 같은 기간이라도 수집 뉴스가 늘어나면 다른 요약 입력이다.

```text
sourceNewsIds = [A, B, C]
sourceNewsIds = [A, B, C, D]
```

## 결정

~~뉴스 요약 결과에는 `newsHash`를 저장한다.~~ ADR 035에서 폐지. 키워드 요약 경로와 `news_summaries`가 없고, story 요약 버전의 중복은 `(storyId, version)`이 막는다(ADR 034).

~~`newsHash`는 다음 값을 기준으로 계산한다.~~

```text
keyword
sourceNewsIds
```

~~계산 방식은 다음과 같다.~~

1. ~~`sourceNewsIds`를 중복 제거하고 정렬한다.~~
2. ~~`keyword`와 정렬된 `sourceNewsIds`를 문자열로 직렬화한다.~~
3. ~~직렬화한 문자열을 SHA-256 hex 문자열로 변환한다.~~

~~MongoDB `news_summaries` collection에는 다음 unique index를 둔다.~~

```text
keyword + newsHash + promptVersion
```

~~저장 전에 같은 키로 선조회해 이미 있으면 그 요약을 재사용하고, 없을 때만 LLM을 호출한다.~~
~~동시 실행으로 선조회를 둘 다 통과한 경우는 unique index가 두 번째 insert를 막고, 그때는 기존 문서를 다시 읽어 반환한다.~~ ADR 035에서 폐지.

~~같은 뉴스 입력이라도 prompt version이 바뀌면 새 요약을 저장할 수 있다.~~
~~`model`과 `provider`는 요약 문서에 기록 필드로 남기지만 저장 키에는 넣지 않는다.~~ ADR 035에서 폐지.

`keyword_expansions` collection은 `keyword + promptVersion`을 unique index로 두고, 저장 전에 선조회해 재사용한다.

### 조회 기간을 hash 입력에 넣지 않는 이유

최초 결정은 `keyword + from + to + sourceNewsIds`를 hash 입력으로 삼았다.
당시 요약 window는 `from = now - lookback`처럼 실행 시각에서 고정 길이로 잘라내는 방식이었고,
같은 window가 반복 실행되는 상황을 재실행의 기본형으로 보았다.

ADR 020에서 window를 watermark 기준으로 바꾸면서 전제가 깨졌다.
watermark 방식의 `to`는 항상 `now`라 실행마다 반드시 달라진다.

```text
실행 N     구간 [10:00, 10:10]   기사 {A, B}   hash = H1   -> LLM 호출, 저장
실행 N+1   구간 [10:05, 10:20]   기사 {A, B}   hash = H2   -> 재사용 실패, LLM 재호출
```

기사 묶음이 완전히 같아도 hash가 달라지므로 기존 요약을 재사용하지 못한다.
ADR 020은 부분 실패 시 watermark를 유지하는 근거로 "다음 실행에서 성공분은 `newsHash`로 재사용되므로
재시도가 추가 비용 없이 이루어진다"를 들었는데, 조회 기간이 hash에 남아 있으면 이 근거가 성립하지 않는다.
실패한 키워드 하나 때문에 매 실행 전체 키워드의 LLM 호출이 다시 발생한다.

요약 결과를 결정하는 것은 실제 기사 묶음이지, 그 묶음을 고른 조회 조건이 아니다.
같은 키워드로 같은 기사 묶음을 요약하면 어떤 구간에서 골랐든 같은 요약이 나온다.
조회 기간은 입력을 고르는 수단이지 입력 자체가 아니다.

반려한 대안은 두 가지다.

- **구간 끝을 고정 시각 경계로 내림**: `to`를 10분 경계(`10:00`, `10:10`)로 내려 연속한 실행이 같은 `to`를 쓰게 하는 방식이다.
  같은 경계 안에서 실행이 두 번 이상 일어날 때만 hash가 맞고 경계를 넘으면 원래 문제로 돌아간다.
  경계 크기가 실행 주기와 엮여, 주기만 바꿨는데 LLM 호출 비용이 조용히 변한다.
  원인(조회 조건이 hash에 들어 있음)은 그대로 두고 그 값이 덜 흔들리게만 만드는 방식이라 증상만 가린다.
- **요약 재사용 판단을 별도 조회 조건으로 분리**: hash는 그대로 두고 "최근 N분 내 같은 키워드 요약이 있으면 재사용"을 추가하는 방식이다.
  중복 판별 기준이 unique index와 조회 로직 두 곳으로 갈라지고, 어느 쪽이 진짜 기준인지 모호해진다.

### model을 저장 키에 넣지 않는 이유

최초 결정은 `model`을 저장 키에 넣어 model이 바뀌면 새 요약을 허용했다. AGGREGATE 모드(보류)에서 model별 요약을
비교하는 상황을 염두에 둔 것이었으나, AGGREGATE도 최종 산출물은 한 건이다.

ADR 021에서 provider 5개를 무작위로 고르고 실패 시 failover하게 되면서 이 키가 문제가 됐다.
provider마다 model이 다르므로 저장 키에 `model`이 있으면 선조회는 같은 provider가 다시 뽑힐 때만 맞는다.
재시도 tick에서 성공분을 재사용한다는 ADR 020/021의 전제가 1/5 확률로만 성립했고,
같은 기사 묶음에 요약 문서가 model별로 최대 5건 쌓였다.
failover가 일어난 tick은 계획한 model과 저장된 model이 달라 다음 tick의 선조회도 빗나갔다.
알림(ADR 022)이 붙으면 같은 뉴스에 알림이 최대 5번 간다.

요약 결과를 결정하는 것은 기사 묶음과 프롬프트다. `model`은 "누가 만들었나"이지 "무엇을 요약했나"가 아니다.

`keyword_expansions`도 같은 이유로 `keyword + promptVersion`으로 줄였다. 이쪽은 index만 바꾸면 provider가 달라도
두 번째 실행이 DuplicateKey로 실패하는 회귀가 생기므로 선조회 재사용을 함께 넣었다.

포기하는 것: model 교체만으로는 재요약이 일어나지 않는다. ~~model 교체 시 promptVersion을 올리는 것을 운용 규칙으로 둔다.~~ ADR 030에서 폐지.
모델 교체는 재요약 대상이 아니다. 요약은 수명이 짧아 옛 뉴스 묶음을 새 모델로 다시 요약하면 같은 뉴스 알림이 다시 나간다.
프롬프트 버전은 프롬프트 본문과 같은 코드 파일(`LlmPrompt`)에 있고, 본문·입력 모양·출력 형식이 바뀔 때만 올린다.

반려한 대안은 두 가지다.

- **키워드별 sticky provider**: failover가 한 번이라도 나면 깨지고, provider 추가·제거로 전 키워드가 재요약된다.
- **index는 두고 선조회만 model 없이**: index에 `model`이 남아 동시 실행 시 다른 provider를 뽑은 두 인스턴스가 둘 다 저장에 성공한다.
  DuplicateKey 방어가 작동하지 않는다.

## 왜 hash를 쓰는가

`newsHash`는 뉴스 문장이나 제목이 비슷한지 판단하지 않는다.
이미 수집된 뉴스에는 고유 id가 있으므로, 요약 입력 동일성은 뉴스 내용 비교가 아니라 뉴스 id 묶음 비교로 판단한다.

뉴스 id 목록을 그대로 unique index에 넣지 않고 hash를 쓰는 이유는 다음과 같다.

- 요약 대상 뉴스가 20개, 50개, 100개로 늘어나도 index key를 고정 길이로 유지한다.
- 배열 필드를 unique index 기준으로 직접 쓰면서 생기는 multikey index 의미 혼란을 피한다.
- `[A, B, C]`와 `[C, B, A]`처럼 순서만 다른 같은 입력을 동일하게 처리한다.
- 중복 판별 키를 `keyword + newsHash + promptVersion`으로 단순하게 유지한다.

## 없을 때

`newsHash`가 없으면 같은 뉴스 묶음으로 만든 요약을 식별하기 어렵다.

예상되는 문제는 다음과 같다.

- 같은 scheduler window가 반복 실행될 때 같은 요약이 여러 번 저장될 수 있다.
- 수동 재시도나 장애 복구 후 재실행에서 LLM 비용이 중복 발생한다.
- notification 단계에서 같은 요약이 여러 번 발송 후보가 될 수 있다.
- 중복을 막기 위해 `keyword + from + to`만 쓰면, 같은 기간에 수집 뉴스가 추가된 경우 새 요약을 저장하지 못한다.

## 있을 때

`newsHash`가 있으면 같은 입력 뉴스 묶음은 같은 hash를 갖는다.

예상되는 효과는 다음과 같다.

- 같은 입력 요약의 중복 저장을 unique index로 차단한다.
- 같은 기간이라도 뉴스 id 묶음이 달라지면 새 요약을 저장할 수 있다.
- prompt 변경에 따른 새 요약 생성을 허용한다.
- 중복 판별 기준이 데이터베이스 index로 명확하게 드러난다.

## 영향

운영 데이터가 없는 단계라 재계산·재색인 마이그레이션은 하지 않는다.
local 데이터는 collection drop 후 재생성 대상으로 둔다. 옛 index는 남아도 무해하나 drop한다.
운영 데이터가 쌓인 뒤 hash 계산식이나 저장 키를 다시 바꾸면 그때는 `newsHash` 재계산 배치와 index 재생성이 필요하다.

## 실험 계획

사전 설계 기준으로는 `newsHash`를 도입한다.
다만 실제 효과는 local 또는 실험 환경에서 전/후 비교값을 남긴다.

실험은 최초 저장 키(`keyword + newsHash + promptVersion + model`) 기준으로 수행했다. 이후 저장 키에서 `model`을
뺐지만(「결정」 참고) 실험이 비교한 것은 `newsHash` 자리에 무엇을 두느냐이고 `model`은 모든 조건에서 같았으므로
결과 해석은 달라지지 않는다.

비교 대상:

- `newsHash` 없이 unique index를 두지 않은 경우
- `sourceNewsIds` 배열을 unique index에 직접 포함하는 경우
- 정렬/중복 제거한 `sourceNewsIdsCanonical` 문자열을 unique index에 포함하는 경우
- `newsHash`를 포함한 현재 설계

반려한 비교안:

- `keyword + promptVersion + model` unique는 같은 키워드의 다른 기간, 다른 뉴스 묶음을 모두 같은 요약으로 취급한다.
- 이 설계는 요구사항을 만족하지 못하므로 성능 실험 대상이 아니라 설계 검토 단계에서 제외한다.

측정 항목:

- 같은 입력을 2회 실행했을 때 저장된 `news_summaries` 개수
- 같은 입력을 2회 실행했을 때 LLM 호출 횟수
- 같은 기간에 뉴스 id 1개가 추가됐을 때 새 요약 저장 가능 여부
- unique index 충돌 발생 횟수
- 반복 실행 총 소요 시간

실험 데이터 구성:

- 대용량 테스트가 필요하면 실제 LLM API를 호출하지 않는다.
- 요약 title/content/sentiment/token usage는 고정 규칙으로 만든 dummy 값으로 채운다.
- 이 실험의 목적은 LLM 품질 측정이 아니라 같은 요약 입력을 MongoDB unique key로 표현하는 방식의 기능 차이와 저장 비용 차이를 확인하는 것이다.

실험 결과 기록:

실제 LLM raw 수집:

- 키워드: `브로드컴 실적`, `SPACE-X IPO`
- provider별 호출 횟수: 각 2회
- 대용량 데이터는 실제 raw seed를 재사용해서 생성했다.

100k 결과:

| 조건 | 1회차 저장 | 1회차 ms | 2회차 저장 | 2회차 실패 | 2회차 ms | 뉴스 추가 저장 | 뉴스 추가 실패 | 뉴스 추가 ms | 최종 수 | storage size | index size |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| unique index 없음 | 100000 | 4000 | 100000 | 0 | 4000 | 100000 | 0 | 5000 | 300000 | 81772544 | 12283904 |
| sourceNewsIds 배열 unique | 100000 | 6000 | 0 | 100000 | 10000 | 0 | 100000 | 9000 | 100000 | 28528640 | 25923584 |
| sourceNewsIdsCanonical unique | 100000 | 5000 | 0 | 100000 | 10000 | 100000 | 0 | 6000 | 200000 | 57483264 | 39624704 |
| newsHash unique | 100000 | 5000 | 0 | 100000 | 9000 | 100000 | 0 | 5000 | 200000 | 57352192 | 45322240 |

1M 결과:

| 조건 | 1회차 저장 | 1회차 ms | 2회차 저장 | 2회차 실패 | 2회차 ms | 뉴스 추가 저장 | 뉴스 추가 실패 | 뉴스 추가 ms | 최종 수 | storage size | index size |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| unique index 없음 | 1000000 | 40000 | 1000000 | 0 | 43000 | 1000000 | 0 | 43000 | 3000000 | 895213568 | 190128128 |
| sourceNewsIds 배열 unique | 1000000 | 64000 | 0 | 1000000 | 99000 | 0 | 1000000 | 102000 | 1000000 | 288178176 | 511332352 |
| sourceNewsIdsCanonical unique | 1000000 | 50000 | 0 | 1000000 | 97000 | 1000000 | 0 | 59000 | 2000000 | 608735232 | 815042560 |
| newsHash unique | 1000000 | 49000 | 0 | 1000000 | 95000 | 1000000 | 0 | 55000 | 2000000 | 611999744 | 570982400 |

## 실험 검증

실험은 합리적인 저장 키 후보를 기준으로 비교했다.

검증 기준:

- 같은 입력을 2회 실행했을 때 중복 저장이 발생하는가
- 같은 기간에 뉴스 id가 추가된 입력을 새 요약으로 저장할 수 있는가
- 대용량에서 저장/충돌 시간이 rows 증가에 따라 납득 가능한 방향으로 증가하는가

### 실험 목적 검증

이 실험의 목적은 뉴스 본문이 서로 비슷한지 판별하는 것이 아니다.
목적은 같은 요약 입력을 다시 저장하려고 할 때 저장소가 중복을 막을 수 있는지 확인하는 것이다.

실험에서 같은 입력은 다음 값이 모두 같은 경우다.

- `keyword`
- `from`
- `to`
- 정렬/중복 제거된 `sourceNewsIds`
- `promptVersion`
- `model`

2회차 실행에서 duplicate key가 계속 발생하는 이유는 실험이 의도적으로 1회차와 동일한 요약 입력을 다시 insert하기 때문이다.
이는 scheduler 재실행, 수동 재시도, 장애 복구 후 재처리 상황을 재현한다.
따라서 duplicate key는 실험 실패가 아니라 "`newsHash` unique index가 같은 입력 재저장을 차단했다"는 관측값이다.

반대로 뉴스 추가 실행은 같은 keyword/from/to라도 `sourceNewsIds`에 새 id가 추가된 입력이다.
이 경우 `newsHash`가 달라져야 하며, 새 요약으로 저장되어야 한다.
이 차이를 확인하기 위해 배열 직접 unique, canonical string unique, newsHash unique를 비교했다.

### 중복 정책 검증

`unique index 없음` 조건은 예상대로 같은 입력 2회차와 뉴스 추가 입력을 모두 새 문서로 저장했다.
100k에서는 최종 300k, 1M에서는 최종 3M 문서가 저장됐다.
이는 중복 요약이 계속 누적되는 baseline이다.

`sourceNewsIds` 배열 unique 조건은 비즈니스 모델과 가장 비슷해 보이지만 요구사항을 만족하지 못했다.
1회차는 모두 저장됐고 같은 입력 2회차도 모두 차단됐다.
하지만 뉴스 id가 추가된 입력도 모두 duplicate key로 차단됐다.
MongoDB 배열 index는 배열 전체를 하나의 scalar 값으로 비교하지 않고 multikey index로 동작한다.
따라서 기존 `[A, B, C]` 입력과 새 `[A, B, C, D]` 입력이 원소를 공유하면 unique index 충돌이 발생할 수 있다.
이 조건은 기능 요구사항을 만족하지 못하므로 설계 후보에서 제외한다.

`sourceNewsIdsCanonical` unique 조건은 설계 의도와 맞았다.
첫 실행은 모두 저장됐고, 같은 입력 2회차는 모두 충돌했으며, 뉴스 id가 추가된 입력은 모두 저장됐다.
다만 뉴스 id 목록을 문자열로 직접 보관하므로 요약 대상 뉴스가 많아질수록 index key가 길어진다.

`newsHash` unique 조건도 설계 의도와 맞았다.
첫 실행은 모두 저장됐고, 같은 입력 2회차는 모두 충돌했으며, 뉴스 id가 추가된 입력은 모두 저장됐다.
100k에서는 최종 200k, 1M에서는 최종 2M 문서가 저장됐다.

### 성능 검증

저장 성공 중심 조건은 rows가 10배 증가할 때 시간이 대체로 9~12배 수준으로 증가했다.

- `unique index 없음` 1회차: 100k 4초, 1M 40초
- `sourceNewsIdsCanonical` 1회차: 100k 5초, 1M 50초
- `newsHash` 1회차: 100k 5초, 1M 49초
- `newsHash` 뉴스 추가: 100k 5초, 1M 55초

이는 대량 insert 관점에서는 납득 가능한 증가다.
MongoDB는 insert할 때 `_id` index와 보조 index를 갱신한다.
unique index가 있으면 새 key가 이미 존재하는지도 확인해야 한다.
저장 성공 케이스에서는 대부분 새 index entry를 순차적으로 추가하므로 row 증가에 따라 시간이 대략 선형으로 증가하는 것이 자연스럽다.

충돌 중심 조건은 저장 성공보다 비용이 컸다.

- `sourceNewsIds` 배열 unique 2회차 충돌: 100k 10초, 1M 99초
- `sourceNewsIdsCanonical` 2회차 충돌: 100k 10초, 1M 97초
- `newsHash` 2회차 충돌: 100k 9초, 1M 95초

대량 재실행에서 duplicate key 충돌을 DB까지 보내는 방식은 비용이 크다.
충돌 케이스에서도 MongoDB는 각 문서마다 unique index key를 만들고, B-tree 계열 index에서 기존 key 존재 여부를 확인한 뒤 duplicate key error를 만들어야 한다.
문서는 저장되지 않더라도 "확인하고 실패 처리하는 비용"은 row 수만큼 발생한다.
따라서 운영 구현에서는 unique index만 믿고 매번 insert를 시도하기보다,
가능하면 저장 키 기준으로 선조회하거나 upsert/idempotent 저장 정책을 추가 검토한다.

MongoDB의 일반적인 query plan은 `find`/aggregation 쿼리 최적화 결과를 보는 도구다.
이번 실험처럼 `mongoimport`/bulk insert 중 unique index가 충돌을 판정하는 과정은 일반 조회 쿼리 plan처럼 직접 나오지 않는다.
대신 `collStats`의 count/storageSize/indexSize, duplicate key 실패 수, 처리 시간을 비교해 저장 정책의 효과를 판단했다.
더 깊게 보려면 MongoDB profiler, serverStatus, FTDC, WiredTiger cache 지표, `$indexStats`를 별도로 수집하는 실험을 추가해야 한다고 
AI에게 제안받았지만, 해당 사전지식이 없음과 다음 개발에 소요가 너무 길어져 여기서 실험은 끝낸다.

크기 관점에서는 1M 기준 `sourceNewsIdsCanonical`의 index size가 약 815MB, `newsHash`의 index size가 약 571MB였다.
이번 데이터는 요약당 뉴스 id가 3~4개뿐인데도 canonical string index가 더 컸다.
요약 대상 뉴스 id 수가 20개, 50개, 100개로 늘어나면 canonical string key는 계속 길어지지만, `newsHash`는 SHA-256 hex 64자로 고정된다.
따라서 `newsHash`의 장점은 단순 insert 시간보다 index key 크기 예측 가능성과 장기적인 index size 억제에 있다.

### 이상값과 한계

100k에서 `newsHash` index size가 `sourceNewsIdsCanonical`보다 컸다.
하지만 1M에서는 `sourceNewsIdsCanonical` index size가 `newsHash`보다 훨씬 컸다.
100k는 초 단위 측정 오차와 WiredTiger 압축/페이지 분할 상태의 영향을 크게 받으므로 장기 경향 판단에는 1M 결과를 우선한다.

100k 시간 측정은 shell에서 초 단위로 측정한 값을 ms로 환산했다.
따라서 100k의 3~10초 구간은 세밀한 latency 비교가 아니라 대략적인 처리 시간으로만 해석한다.
1M 결과가 성능 판단에 더 적합하다.

실험은 Mongo 단일 container, local Docker 환경에서 수행했다.
실제 운영 성능은 MongoDB 리소스, 디스크, write concern, batch 크기, 네트워크 조건에 따라 달라질 수 있다.

## 결과

- ~~`newsHash`는 뉴스 중복 판별용 값이 아니라 뉴스 요약 입력 묶음의 fingerprint다.~~ ADR 035에서 폐지.
- `newsHash`는 겹친 조회 구간의 재요약을 막지 못했다. 막는 것은 완전히 같은 묶음의 재저장뿐이었다.
  - 묶음에 새 기사가 하나라도 더해지면 다른 hash가 된다(2026-09-07 관측, ADR 031).
- story 요약은 `(storyId, version)`이 중복을 막는다(ADR 034).
- ~~저장 중복 방지 기준은 `keyword + newsHash + promptVersion`으로 둔다. `model`·`provider`는 기록 필드다.~~ ADR 035에서 폐지.
- 실험 결과는 위 표에 누적하고, 실제 운영 정책은 중복 저장 차단과 idempotent 재실행 처리 요구가 확정될 때 다시 조정한다.
