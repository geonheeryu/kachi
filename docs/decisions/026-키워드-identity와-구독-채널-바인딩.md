# 026. 키워드 identity와 구독, 채널 바인딩

## 배경

ADR 025는 "누가 어떤 키워드를 어떤 채널로 받는가"를 user-service가 소유하기로 했다.  
이 ADR은 그 결정을 user-service 안에서 어떻게 구현하는지 정한다.

지금 user-service의 `Keyword`는 사용자별 aggregate다.  
`Keyword(userId, name)`이 `(user_id, name)` unique로 저장되고, 활성 키워드 internal API는 이름 문자열을 대소문자를 구분한 채 중복 제거해 내보낸다.   
`tesla`, `Tesla`, ` TESLA `는 세 키워드이고, collector와 ai는 세 번 수집하고 세 번 요약한다. 사용자의 알림 채널과 주소는 아직 없는 정보이다.  

정해야 할 것은 다음이다.  

1. 사용자별 `Keyword`와 ADR 025의 canonical 키워드·구독의 관계. 기존 aggregate를 어떻게 바꾸는가.
2. 키워드를 받아 쓰는 collector·ai·notification routing이 사용하는 키워드 문자열을 활성 키워드 API가 어떤 값으로 내보내는가.
3. 채널 바인딩의 생명주기와 주소 저장 방식. Telegram처럼 사용자가 주소를 직접 입력할 수 없는 채널의 연결 절차.
4. internal API 3개의 계약과 보안 경계.

## 결정

### canonical 키워드와 구독

`Keyword`를 **canonical 키워드**로 재정의한다.  
사용자와 무관하게 정규화 값(`canonicalKey`) 하나에 행 하나이며, 최초 등록 원문을 `displayName`으로 보관한다.  
사용자별 역할은 새 aggregate `Subscription(userId, keywordId, channels, enabled)`이 승계한다.  
기존 `keywords` 테이블은 `subscriptions`가 되고, canonical `keywords`는 새로 만든다.

```
사용자 A ─ Subscription(A, K, [SLACK])   ─┐
                                        ├─ Keyword K (canonicalKey = "tesla", displayName = "Tesla")
사용자 B ─ Subscription(B, K, [TELEGRAM]) ┘
```

ADR 025가 활성 키워드 API에 `keywordId`를 처음부터 싣기로 한 이유는 나중에 이름 변경·병합의 identity로 옮기기 위해서다.  
사용자별 `Keyword`에 `canonical_key` 컬럼만 더하는 방식은 코드 변경이 가장 작지만 `keywordId`가 사용자마다 달라 그 목적에 맞지 않는다.  
canonical 행을 지금 두면 identity가 처음부터 안정적이고, 그 대가는 기존 aggregate 하나의 이름과 역할이 바뀌는 것이다.  

정규화 규칙은 ADR 025 그대로다.  
"양끝 구두점"은 Unicode 일반 카테고리 P*(구두점)에 속하는 문자이고, 기호(S*)는 남긴다.  
`"SPACE-X"`의 중간 하이픈과 `"$TSLA"`의 `$`는 유지되고 `"테슬라."`의 마침표는 제거된다.  
정규화 결과가 비면 거부한다.

구독의 unique 키는 `(userId, keywordId)`다.  
canonical 행은 `canonicalKey`당 하나이므로 이는 ADR 025의 `(userId, canonicalKey)`와 같은 키다.  
`canonical_key` 컬럼은 `utf8mb4_bin`으로 둔다.
대소문자를 무시하는 collation이면 DB의 동등성이 코드의 정규화보다 느슨해져 중복 판정이 두 곳으로 갈라진다.  
같은 사용자가 `Tesla`를 등록한 뒤 `tesla`를 등록하면 중복이다.  
구독은 채널을 하나 이상 가지며, 등록·변경 시점에 각 채널의 바인딩이 `ACTIVE`여야 한다. 아니면 `409 CHANNEL_BINDING_NOT_ACTIVE`다 — 요청 형식은 맞고 사용자의 현재 상태와 충돌하는 것이라 400이 아니다.  
구독 채널은 `SLACK`·`DISCORD`·`TELEGRAM` 세 개다. 사용자가 고를 수 있는 채널만 두며, notification-contract의 채널 enum과는 별개다(bounded context 간 코드 의존 금지).  
바인딩이 나중에 해지되면 구독은 그대로 두고 internal 조회가 그 채널을 걸러낸다.  
해지는 사용자 행위이고, 다시 연결하면 구독이 그대로 살아나야 한다.

키워드 이름 변경 API는 없앤다. 이름이 다르면 다른 키워드다(ADR 025).

### 활성 키워드 API의 `name`은 canonicalKey다

collector·ai·routing이 키워드를 맞춰 보는 join key는 정규화된 문자열이다(ADR 025).  
collector와 ai는 응답의 `name`만 읽어 그 문자열로 뉴스를 수집·저장하고, ai는 그 값을 이벤트의 `keyword`로 발행한다.  
routing은 그 `keyword`로 `GET /internal/subscriptions?keyword=`를 묻는다.  
`name`이 원문이면 routing이 조회 전에 정규화를 한 번 더 해야 하고, 두 서비스가 같은 normalize 구현을 가져야 한다.  
그래서 `name`에 canonicalKey를 보낸다. 응답에는 `keywordId`, `canonicalKey`, `displayName`을 함께 싣는다.  
collector·ai의 클라이언트 코드는 바뀌지 않는다(추가 필드는 무시된다).

대가는 수집·요약이 소문자 정규화 문자열로 돈다는 것이다.  
검색 provider는 대소문자를 구분하지 않고, LLM 프롬프트의 키워드도 마찬가지라 결과 품질에 영향은 없다고 본다.  
사람에게 보이는 이름이 필요한 곳(알림 본문)은 routing이 `displayName`을 쓴다.

### 채널 바인딩

`ChannelBinding(userId, channel, address, status)`은 사용자·채널당 하나다. 알림 쪽은 바인딩을 `(userId, channel)`로 가리키므로(ADR 025) 바인딩 id는 밖으로 나가지 않는다.

| 상태 | 뜻 |
| --- | --- |
| `PENDING` | Telegram 연결 토큰을 발급했고 chat id가 아직 없다 |
| `ACTIVE` | 주소가 있고 발송 가능하다 |
| `REVOKED` | 사용자가 해지했다. 주소는 지운다 |

- Slack·Discord는 사용자가 자기 incoming webhook URL을 등록한다. URL은 채널별 host prefix(`https://hooks.slack.com/`, `https://discord.com/api/webhooks/`)를 검증한다. worker가 이 URL로 HTTP를 보내므로 임의 host를 받으면 안 된다.
- Telegram은 사용자가 chat id를 모른다. 연결 토큰(무작위 32바이트, 10분 만료)을 발급해 `https://t.me/{bot}?start={token}` 링크를 돌려주고, 봇이 `/start <token>`을 받으면 `POST /internal/channel-bindings/telegram/link {token, chatId}`로 바인딩이 `ACTIVE`가 된다. 토큰 원문은 `LinkToken`으로 응답에 한 번만 나가고, aggregate와 저장소에는 SHA-256 해시(`LinkTokenHash`)와 만료 시각만 있다. "해시만 저장"을 지키려면 aggregate가 원문을 들 수 없기 때문이다. 봇 업데이트를 받는 쪽은 Telegram API를 호출하는 notification-worker의 관심사라 이 ADR 범위 밖이다.
- 재등록은 새 행이 아니라 기존 행 갱신이다. `(userId, channel)`이 그대로라 진행 중인 알림이 새 주소로 간다.
- 해지는 주소를 null로 지운다. 보관할 이유가 없다.

주소는 AES-256-GCM으로 암호화해 저장한다(Spring Security `AesBytesEncryptor`, 키는 env `KACHI_USER_BINDING_KEY`). 암호화마다 무작위 IV(Initialization Vector)를 새로 뽑아 암호문 앞에 붙여 저장하므로 같은 주소도 행마다 암호문이 다르고, 암호문끼리 비교해 같은 주소인지 알아낼 수 없다. 행마다 `key_version`을 두어 키 교체를 행 단위로 진행할 수 있게 하되, 교체 절차 자체는 후속이다. 도메인의 `ChannelAddress`는 `toString()`이 마스킹된 값을 돌려준다. 로그에 주소가 남는 경로를 타입에서 막는다.

### internal API

| API | 응답 | 소비자 |
| --- | --- | --- |
| `GET /internal/keywords/active` | `[{keywordId, canonicalKey, displayName, name}]` — enabled 구독이 하나 이상인 키워드 | collector, ai |
| `GET /internal/subscriptions?keyword=` | `[{userId, channel}]` — enabled 구독 x 채널 중 바인딩 ACTIVE만. 없는 키워드는 `[]` | notification-routing |
| `POST /internal/subscriptions/recipients/search` | `{keywords, recipients: [{userId, channel, keywordIndexes}], nextCursor}`. 여러 키워드의 수신자를 사용자 단위 keyset 커서로 나눠 돌려준다 | notification-routing |
| `GET /internal/users?role=` | `[{userId, channels}]` — 그 역할의 ACTIVE 사용자 중 ACTIVE 바인딩이 있는 사용자와 그 채널. 바인딩 없는 사용자는 뺀다 | notification-routing (키워드 격리 알림) |
| `GET /internal/users/{userId}/channel-bindings/{channel}` | `{channel, status, address}` — ACTIVE일 때만 복호화 주소, 아니면 `address: null`. 바인딩이 없으면 404 | notification-worker (W1) |
| `POST /internal/channel-bindings/telegram/link` | `{token, chatId}` -> 204. 토큰 무효·만료는 400 `LINK_TOKEN_INVALID` | Telegram 봇 수신기 (후속) |

응답은 모두 활성 키워드 API와 같은 `{success, data, error}` 래핑이다.

구독 조회는 `keyword`를 `CanonicalKey.of`로 한 번 더 정규화해 찾는다. 활성 키워드 API의 `name`을 그대로 보내면 되고 원문이 와도 같은 결과다. 결과는 `userId`, `channel` 순으로 정렬하며, 바인딩은 구독자 집합으로 한 번에 읽어 구독 수만큼 조회하지 않는다. 해지된 바인딩의 채널은 여기서 빠지고 구독은 그대로다.

바인딩 조회는 `ref`가 UUID 형식이 아니면 400, 없는 ref면 404 `CHANNEL_BINDING_NOT_FOUND`다. `PENDING`·`REVOKED`는 상태만 돌려주고 주소는 `null`이다. 주소가 없는 응답을 받은 worker는 그 알림을 `SUPPRESSED`로 끝낸다(W1).

공개 API의 응답 코드는 다음과 같다.

| 코드 | 상태 | 경우 |
| --- | --- | --- |
| `DUPLICATE_SUBSCRIPTION` | 409 | 같은 사용자가 같은 canonical 키워드를 다시 구독 |
| `CHANNEL_BINDING_NOT_ACTIVE` | 409 | 구독 등록·변경 시 채널의 바인딩이 `ACTIVE`가 아님 |
| `CHANNEL_BINDING_NOT_FOUND` | 404 | 해지·조회할 바인딩이 없음 |
| `INVALID_CHANNEL_ADDRESS` | 400 | 채널별 주소 형식 위반 |
| `LINK_TOKEN_INVALID` | 400 | 연결 토큰 무효·만료 |

internal API는 인증을 두지 않고 `/api/v1/internal/**`를 `permitAll`로 연다. 지금 이 API들은 같은 호스트의 서비스끼리만 호출하고 외부 ingress가 없다. 구독 조회는 userId를, 바인딩 조회는 복호화한 주소를 돌려주므로 외부에 노출되면 안 되는 API이고, **ingress를 붙이는 시점이 공유 토큰 인증(`X-Internal-Token` 필터 + 호출자 세 곳의 헤더)을 넣는 단계의 착수 조건**이다. 지금 넣지 않는 이유는 호출자 collector·ai를 같은 PR에서 바꿔야 해 브랜치가 모듈 하나를 넘기 때문이다.

#### 구독 수신자 조회

`POST /api/v1/internal/subscriptions/recipients/search`는 여러 키워드의 수신자를 사용자 단위 페이지로 돌려준다. 조회인데 POST인 이유는 키워드 수가 요청마다 달라 URL 길이에 묶이지 않게 하려는 것이다.

```
// 요청
{"keywords": ["nvidia", "tsmc"], "cursor": null, "limit": 100}

// 응답 data
{
  "keywords": [{"canonicalKey": "nvidia", "displayName": "NVIDIA"}],
  "recipients": [{"userId": "0190f3a2-7c4e-7b1a-9d2e-3f4a5b6c7d8e", "channel": "SLACK", "keywordIndexes": [0]}],
  "nextCursor": "0190f3a2-7c4e-7b1a-9d2e-3f4a5b6c7d8e"
}
```

- 요청 키워드는 서버가 `CanonicalKey.of`로 정규화한다.
- 응답 `keywords`는 요청 키워드 중 존재하는 canonical 키워드만 `canonicalKey` 오름차순으로 담는다. 없는 키워드는 무시한다.
- 수신자는 `(userId, channel)`이다. enabled 구독의 채널 중 그 사용자의 ACTIVE 바인딩이 있는 것만 센다.
- `keywordIndexes`는 그 사용자가 그 채널로 구독한 키워드를 응답 `keywords` 배열의 위치로 가리키며 오름차순이다. 항목이 키워드 문자열 대신 위치를 들어 항목 크기가 키워드 길이에 비례하지 않는다.
- 페이징은 keyset 커서다. 페이지 단위는 사용자이고 `limit`은 사용자 수이며, 한 사용자의 항목은 모두 한 페이지에 들어간다.
- `cursor`는 앞 페이지의 마지막 userId다. `nextCursor`가 null이면 끝이다.
- 수신자가 0명인 페이지에도 `nextCursor`가 있을 수 있다. 그 페이지의 사용자가 모두 ACTIVE 바인딩이 없는 경우다.
- `keywords`는 1~100개, `limit`은 1~100(기본 100)이다. 벗어나거나 `cursor`가 UUID 형식이 아니면 400이다.
- 정규화하면 빈 값이 되거나 100자를 넘는 키워드가 요청에 있으면 400이다.

키워드마다 `keyword_id = ? and enabled and user_id > :cursor order by user_id limit :limit + 1`로 읽고, 결과를 userId 순으로 병합해 앞의 `limit`명을 고른다. 페이지에 드는 사용자는 어느 키워드의 결과에서도 앞의 `limit`명 안에 있으므로 그 사용자의 구독은 빠짐없이 읽힌다. 병합한 사용자가 `limit`명을 넘으면 `nextCursor`는 그 페이지의 마지막 userId이고, 아니면 null이다.

인덱스는 `(keyword_id, enabled, user_id)`다. 키워드마다 이 인덱스를 user_id 순으로 읽다 `limit + 1`행에서 멈추므로, 한 페이지의 비용은 남은 구독자 수가 아니라 `키워드 수 x limit`에 비례한다.

병합 순서는 DB의 `BINARY(16)` 비교, 곧 부호 없는 바이트 순이어야 한다. Java `UUID.compareTo`는 상위·하위 64비트를 부호 있는 `long`으로 비교해 `80000000-...`을 `00000000-...`보다 작게 본다. 병합 순서가 DB와 어긋나면 커서 앞뒤에서 사용자가 빠지거나 겹친다.

#### 응답 크기 상한

최악은 `limit` 100, `keywords` 100개이고, 모든 사용자가 100개 키워드를 세 채널(`SLACK`, `DISCORD`, `TELEGRAM`)로 모두 구독한 경우다. `canonical_key`·`display_name`은 `varchar(100)`이고 코드(`CanonicalKey`, `KeywordName`)도 100자(UTF-16 code unit)를 넘으면 거부한다. 제어 문자는 JSON에서 `\u0001`처럼 6바이트로 이스케이프되므로 문자열 하나는 최대 600바이트다.

```
keywords 항목    {"canonicalKey":"..","displayName":".."}           36 + 600 x 2                       =   1,236
keywords 배열    1,236 x 100 + 쉼표 99 + 괄호 2                                                         = 123,701
keywordIndexes   [0,1,..,99]                                         괄호 2 + 숫자 190 + 쉼표 99          =     291
recipients 항목  {"userId":"<UUID 36>","channel":"..","keywordIndexes":[..]}   371 + 채널 이름 5·7·8      = 376·378·379
recipients 배열  (376 + 378 + 379) x 100 + 쉼표 299 + 괄호 2                                            = 113,601
래핑             {"success":true,"data":{..,"nextCursor":"<UUID 36>"},"error":null}                     =     116
합계                                                                                                    = 237,418
```

237,418바이트는 notification-routing의 `max-in-memory-size` 262,144바이트의 90.6%이고, 24,726바이트가 남는다.

## 검토한 대안

- **사용자별 `Keyword`에 `canonical_key` 컬럼 추가**
  - 변경이 가장 작지만 `keywordId`가 사용자마다 달라 이름 변경·병합의 identity가 되지 못한다.
- **활성 키워드 API의 `name`을 원문으로 두고 routing이 정규화**
  - normalize 구현이 두 서비스에 생긴다. 하나가 바뀌면 조회가 조용히 빈 결과를 낸다.
- **바인딩을 채널당 여러 행, 해지 행 보존**
  - 알림 쪽이 바인딩 id를 들고 있어야 하고 REVOKED 행 정리가 필요하다. 사용자·채널당 하나면 둘 다 없다.
- **주소를 평문 저장**
  - webhook URL은 그 자체로 발송 권한이다. DB dump가 곧 발송 권한 유출이다.
- **KMS·envelope 암호화**
  - 키 하나를 env로 받는 것과 비교해 지금 규모에서 얻는 것이 없다. `key_version`으로 교체 경로만 남긴다.
- **internal API 공유 토큰을 지금**
  - 호출자 두 모듈을 같이 바꿔야 한다. 착수 조건(ingress)을 적어 두는 것으로 대신한다.
- **Java 마이그레이션으로 기존 키워드를 정규화해 이관**
  - 이관할 데이터가 없는 단계에서 정규화 로직을 마이그레이션에 복제하는 비용만 남는다.
- **수신자 조회를 `keyword_id in (...) order by user_id limit` 한 문장으로**
  - 인덱스가 `keyword_id`별로 user_id 순서를 가지므로 여러 키워드에 걸친 user_id 순서로 읽다 멈추지 못한다. 조건에 맞는 구독을 모두 읽고 정렬한 뒤 자르므로 비용이 남은 구독자 수에 비례한다.

## 트레이드오프

- 장점:
  - 같은 키워드를 몇 명이 등록해도 canonical 행은 하나라 수집·요약이 한 번이다.
  - `keywordId`가 처음부터 사용자와 무관한 identity다.
  - 주소가 DB에 평문으로 없고, 이벤트·로그에는 사용자 id와 채널만 남는다.
  - collector·ai 클라이언트는 바뀌지 않는다.
- 단점:
  - 기존 `Keyword` aggregate의 이름과 역할이 바뀐다. 이름 변경 API가 사라진다.
  - collector·ai·routing이 다루는 키워드 문자열이 소문자 정규화 값이다.
  - 암호화 키 하나가 운영 비밀에 추가된다.
  - Telegram 연결은 봇 수신기가 붙기 전까지 `PENDING`에서 끝난다.
  - 수신자 조회의 페이지 사이에 구독이 바뀌면, 커서 앞쪽 userId에 새로 생긴 구독은 그 조회에서 빠진다.

## 후속

- Telegram 봇 업데이트 수신기 (`/start` -> link internal API)
- internal API 공유 토큰 — ingress 도입 시
- 암호화 키 교체 절차
- Telegram 403 -> 바인딩 `BOUNCED`
- 운영자 alias 표 (ADR 025)
