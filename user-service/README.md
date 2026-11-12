# user-service

사용자 인증, 사용자 상태, 키워드 구독, 채널 바인딩을 관리하는 서비스다.

`collector-service`·`ai-service`는 수집 대상 키워드를 직접 소유하지 않고 이 서비스의 internal API에서 활성 키워드를 조회한다.
notification-routing과 notification-worker는 같은 internal API로 키워드·역할의 수신자와 수신 주소를 조회한다.

## 구현 상태

- `User`, `Keyword`(canonical), `Subscription`, `ChannelBinding` 도메인 모델
- OAuth2 provider 기반 사용자 식별
- Google, Kakao, Naver OAuth2 사용자 정보 정규화
- JWT access token / refresh token 발급
- Redis 기반 refresh token 저장, 회전, 로그아웃
- MySQL 기반 사용자/키워드/구독/채널 바인딩 persistence adapter (바인딩 주소는 AES-256-GCM 암호문)
- 사용자 등록, 내 정보 조회, 탈퇴 API
- 내 키워드 구독 등록, 조회, 수정 API
- 내 채널 바인딩 등록(Slack·Discord webhook, Telegram 연결 링크), 조회, 해지 API
- 활성 키워드, 키워드·역할별 수신자, 구독 수신자 페이지, 수신 주소, Telegram 연결 internal API
- Spring Security 기반 JWT 인증 필터
- Actuator health endpoint

## API

HTTP API는 `/api/v1` prefix를 사용한다.

### Auth

Access token 재발급:

```http
POST /api/v1/auth/token/refresh
```

```json
{
  "refreshToken": "..."
}
```

로그아웃:

```http
POST /api/v1/auth/logout
```

```json
{
  "refreshToken": "..."
}
```

로그아웃은 refresh token 저장소에서 해당 token을 제거해 추가 재발급을 막는다.

### User

사용자 등록:

```http
POST /api/v1/users
```

```json
{
  "email": "user@example.com",
  "nickname": "user",
  "authProvider": "LOCAL",
  "providerUserId": null
}
```

내 정보 조회:

```http
GET /api/v1/me
Authorization: Bearer {accessToken}
```

내 계정 탈퇴:

```http
DELETE /api/v1/me
Authorization: Bearer {accessToken}
```

### Keyword 구독

키워드는 정규화 값(`canonicalKey`) 하나에 행 하나인 canonical 키워드이고, 사용자별 관심은 구독(`Subscription`)이 가진다.
`Tesla`, `tesla`, ` TESLA `는 같은 키워드다. 이름 변경은 없다 — 이름이 다르면 다른 키워드다.

내 구독 목록 조회:

```http
GET /api/v1/me/keywords
Authorization: Bearer {accessToken}
```

구독 등록:

```http
POST /api/v1/me/keywords
Authorization: Bearer {accessToken}
```

```json
{
  "name": "NVIDIA",
  "channels": ["SLACK", "TELEGRAM"]
}
```

응답에는 `id`(구독), `keywordId`, `name`(최초 등록 원문), `canonicalKey`, `channels`, `enabled`가 온다.
같은 사용자가 같은 canonical 키워드를 다시 등록하면 `409 DUPLICATE_SUBSCRIPTION`이다.
채널마다 그 사용자의 바인딩이 `ACTIVE`여야 한다. 아니면 `409 CHANNEL_BINDING_NOT_ACTIVE`다. 
먼저 아래 [채널 바인딩]으로 채널을 연결한다.

구독 수정(채널·활성 여부):

```http
PATCH /api/v1/me/keywords/{subscriptionId}
Authorization: Bearer {accessToken}
```

```json
{
  "channels": ["DISCORD"],
  "enabled": true
}
```

`enabled=false`인 구독은 수집·요약·알림 대상에서 제외된다.

### 채널 바인딩

채널 바인딩은 사용자가 그 채널로 알림을 받을 주소다. 사용자·채널당 하나이며, 재등록·해지·재연결은 같은 행의 상태 전이다.
주소는 암호화해 저장하고 응답에는 마스킹한 값만 나간다.

| 상태 | 뜻 |
| --- | --- |
| `PENDING` | Telegram 연결 링크를 발급했고 chat id가 아직 없다 |
| `ACTIVE` | 주소가 있고 발송할 수 있다 |
| `REVOKED` | 사용자가 해지했다. 주소는 지워져 있다 |

내 바인딩 목록:

```http
GET /api/v1/me/channel-bindings
Authorization: Bearer {accessToken}
```

```json
{ "success": true, "data": [{ "channel": "SLACK", "status": "ACTIVE", "addressMasked": "https://hooks.slack.com/****", "boundAt": "…", "revokedAt": null }], "error": null }
```

Slack·Discord 연결 — 자기 incoming webhook URL을 등록한다. 즉시 `ACTIVE`가 된다:

```http
PUT /api/v1/me/channel-bindings/SLACK
Authorization: Bearer {accessToken}
```

```json
{ "webhookUrl": "https://hooks.slack.com/services/…" }
```

Slack은 `https://hooks.slack.com/`, Discord는 `https://discord.com/api/webhooks/`나 `https://discordapp.com/api/webhooks/`로 시작해야 한다. 아니면 `400 INVALID_CHANNEL_ADDRESS`다.

Telegram 연결 — 본문 없이 호출하면 연결 링크를 돌려주고 바인딩은 `PENDING`이 된다:

```http
PUT /api/v1/me/channel-bindings/TELEGRAM
Authorization: Bearer {accessToken}
```

```json
{ "success": true, "data": { "linkUrl": "https://t.me/kachi_local_bot?start=…", "expiresAt": "…" }, "error": null }
```

사용자가 링크를 열어 봇에 `/start <token>`을 보내면 봇 수신기가 아래 internal API로 chat id를 넘겨 `ACTIVE`가 된다. 봇 수신기는 (계획, 미구현)이고 internal API만 있다. 링크는 10분 뒤 만료되며 다시 PUT하면 새 링크가 나온다.

해지:

```http
DELETE /api/v1/me/channel-bindings/{channel}
Authorization: Bearer {accessToken}
```

204를 돌려주고 주소를 지운다. 해지해도 그 채널을 쓰는 구독은 그대로이며 수신자 조회에서만 빠진다. 다시 연결하면 구독이 그대로 살아난다.

### Internal

internal API는 `/api/v1/internal/**`이며 인증 없이 열려 있다. 같은 호스트의 서비스끼리만 호출한다는 전제이고,
외부 ingress를 붙이는 시점이 공유 토큰 인증을 넣는 조건이다.
응답은 모두 `{ "success", "data", "error" }`로 감싼다.

활성 키워드 조회 — collector-service·ai-service가 호출한다:

```http
GET /api/v1/internal/keywords/active
```

```json
{
  "success": true,
  "data": [{ "keywordId": "…", "canonicalKey": "space-x", "displayName": "SPACE-X", "name": "space-x" }],
  "error": null
}
```

enabled 구독이 하나 이상인 canonical 키워드를 `canonicalKey` 순으로 돌려준다. `name`은 `canonicalKey`와 같은 값이다 —
소비자는 `name`만 읽고 그 문자열로 수집·요약·라우팅을 이어가므로 정규화는 이 서비스 한 곳에서만 한다.

구독 수신자 조회 — notification-routing이 여러 키워드의 수신자를 사용자 단위 페이지로 물을 때 호출한다:

```http
POST /api/v1/internal/subscriptions/recipients/search
```

```json
{ "keywords": ["nvidia", "tsmc"], "cursor": null, "limit": 100 }
```

```json
{
  "success": true,
  "data": {
    "keywords": [{ "canonicalKey": "nvidia", "displayName": "NVIDIA" }],
    "recipients": [{ "userId": "…", "channel": "SLACK", "keywordIndexes": [0] }],
    "nextCursor": "…"
  },
  "error": null
}
```

요청 키워드는 정규화한 뒤 중복을 없애고, 존재하는 키워드만 `canonicalKey` 오름차순으로 `keywords`에 담는다.
없는 키워드만 주면 `keywords`·`recipients`가 빈 배열이고 `nextCursor`는 `null`이다.

수신자 항목은 `(userId, channel)` 하나이고 `userId`, `channel` 순으로 담는다.
`userId`가 수신자 식별자(recipientId)이며 주소는 싣지 않는다.
enabled 구독의 채널 중 그 사용자의 `ACTIVE` 바인딩이 있는 채널만 항목이 된다.
`keywordIndexes`는 그 채널로 구독한 키워드의 `keywords` 배열 위치를 오름차순으로 가리킨다.

`limit`은 사용자 수이고 한 사용자의 항목은 모두 한 페이지에 들어간다.
`cursor`에는 앞 페이지의 `nextCursor`를 보내고, `nextCursor`가 `null`이면 마지막 페이지다.
페이지의 사용자가 모두 `ACTIVE` 바인딩이 없으면 `recipients`가 비어 있어도 `nextCursor`가 있다.
`userId` 순서는 UUID 16바이트의 부호 없는 바이트 순이다.

다음 요청은 `400 INVALID_REQUEST`다.

- `keywords`가 0개이거나 100개를 넘거나 null·blank 원소가 있다.
- `limit`이 1~100을 벗어난다. 생략하면 100이다.
- `cursor`가 UUID 형식이 아니다.
- 정규화하면 빈 값이 되거나 100자를 넘는 키워드가 있다.

역할별 수신자 조회 — notification-routing이 키워드 격리 이벤트의 수신자를 물을 때 호출한다:

```http
GET /api/v1/internal/users?role=ADMIN
```

```json
{
  "success": true,
  "data": [{ "userId": "…", "channels": ["SLACK", "TELEGRAM"] }],
  "error": null
}
```

그 역할의 `ACTIVE` 사용자 중 `ACTIVE` 바인딩이 하나 이상인 사용자를 `userId` 순으로 돌려주고, `channels`는 그 바인딩의 채널이다.
바인딩이 없는 사용자는 빠진다. `role`이 없거나 모르는 값이면 400이다.

수신 주소 조회 — notification-worker가 발송 직전에 호출한다:

```http
GET /api/v1/internal/users/{userId}/channel-bindings/{channel}
```

```json
{
  "success": true,
  "data": { "channel": "SLACK", "status": "ACTIVE", "address": "https://hooks.slack.com/services/…" },
  "error": null
}
```

`ACTIVE`면 복호화한 평문 주소가 오고, `PENDING`·`REVOKED`면 `address`가 `null`이다(worker는 그 알림을 SUPPRESSED로 끝낸다).
그 사용자에게 그 채널의 바인딩이 없으면 `404 CHANNEL_BINDING_NOT_FOUND`, `userId`가 UUID 형식이 아니거나 `channel`이 모르는 값이면 400이다.

Telegram 연결 완료 — 봇 수신기가 `/start <token>`을 받았을 때 호출한다:

```http
POST /api/v1/internal/channel-bindings/telegram/link
```

```json
{ "token": "…", "chatId": "123456789" }
```

204를 돌려주고 바인딩이 `ACTIVE`가 된다. 토큰이 없거나 만료됐으면 `400 LINK_TOKEN_INVALID`다.

## OAuth2 로그인

OAuth2 로그인은 Spring Security OAuth2 Client 흐름을 사용한다.

```text
GET /oauth2/authorization/{registrationId}
  -> provider 인증
  -> /login/oauth2/code/{registrationId}
  -> 내부 사용자 조회/등록
  -> JWT access/refresh token 발급
  -> kachi.oauth2.success-redirect-uri 로 redirect
```

지원 provider:

- `google`
- `kakao`
- `naver`

OAuth2 authorization request는 서버 세션 대신 서명된 쿠키에 저장한다.
성공 후 token은 redirect URI fragment로 전달한다.

## 인증 정책

| 토큰 | 기본 TTL | 저장소 | 용도 |
| --- | --- | --- | --- |
| Access Token | 15분 | 저장하지 않음 | API 요청 인증 |
| Refresh Token | 14일 | Redis | access token 재발급 |

JWT claim은 `jti`, `sub`, `type`, `role` 중심으로 최소화한다.
Refresh token은 Redis에 저장되며, 재발급 시 기존 token을 폐기하고 새 token으로 회전한다.

## 저장소

`user-service`는 MySQL과 Redis를 사용한다.

MySQL:

- `users`
- `keywords` (canonical)
- `subscriptions`, `subscription_channels`
- `channel_bindings` (주소는 AES-256-GCM 암호문, `key_version`으로 키 교체 대비)

Redis:

- `refresh_token:{userId}:{jti}`

Schema 변경은 Flyway migration으로 관리하고, JPA `ddl-auto`는 `validate`로 둔다.

## 설정

| 키 | 환경변수 | 뜻 |
| --- | --- | --- |
| `kachi.user.binding.encryption-key` | `KACHI_USER_BINDING_KEY` | 바인딩 주소 암호화 키. base64로 표기한 32바이트 |
| `kachi.user.binding.link-token-ttl` | - | Telegram 연결 링크 만료. 기본 `10m` |
| `kachi.user.telegram.bot-username` | `KACHI_USER_TELEGRAM_BOT_USERNAME` | 연결 링크 `https://t.me/{bot}?start=`의 봇 계정명 |
| `kachi.jwt.secret` | `KACHI_JWT_SECRET` | access token 서명 키 |
| `kachi.oauth2.success-redirect-uri` | `KACHI_OAUTH2_SUCCESS_REDIRECT_URI` | OAuth2 로그인 성공 뒤 token을 넘길 frontend 주소 |
| `kachi.user.seed.admin-addresses.{slack,discord,telegram}` | `KACHI_USER_SEED_ADMIN_SLACK_WEBHOOK_URL`, `KACHI_USER_SEED_ADMIN_DISCORD_WEBHOOK_URL`, `KACHI_USER_SEED_ADMIN_TELEGRAM_CHAT_ID` | local 프로필에서 관리자 사용자에게 심는 채널 주소. 비우면 심지 않는다 |

로컬 값은 `.env.example`에 있다. 암호화 키는 운영 비밀이며 바꾸면 기존 암호문을 복호화할 수 없다.

## 실행

아래 명령은 repository root에서 실행한다.

로컬 인프라 실행:

```sh
docker compose -f infra/docker-compose.yml -f infra/docker-compose.mysql.yml -f infra/docker-compose.redis.yml up -d
```

로컬 환경변수 로드:

```sh
set -a
source .env.local
set +a
```

실행:

```sh
./gradlew :user-service:bootRun
```

Health check:

```sh
curl "http://localhost:8080/actuator/health"
```

테스트:

```sh
./gradlew :user-service:test
```

## 관련 문서

- [OAuth2 로그인 흐름](../docs/decisions/006-oauth2-로그인-흐름.md)
- [JWT Access/Refresh Token 정책](../docs/decisions/004-jwt-access-refresh-token-정책.md)
- [user-service에 Keyword 포함](../docs/decisions/001-user-service에-keyword-포함.md)
- [user-service 운영 기본 설정](../docs/decisions/007-user-service-운영성-기본설정.md)
- [키워드 구독과 알림 라우팅](../docs/decisions/025-키워드-구독과-알림-라우팅.md)
- [키워드 identity와 구독, 채널 바인딩](../docs/decisions/026-키워드-identity와-구독-채널-바인딩.md)
