# User 컨텍스트 도메인 모델

user-service의 도메인 모델이다. 사용자 인증·상태, 키워드 구독, 채널 바인딩을 담당한다.
공통 관례(생성/복원 분리, id VO, Clock 주입)는 [도메인모델.md](도메인모델.md)를 따른다.

## 사용자 애그리거트

### 사용자(User)

_Aggregate Root_

#### 속성(Attributes)

- `id`: `UserId` 사용자 식별자
- `email`: `Email` 로그인 이메일 - Natural ID
- `nickname`: `Nickname` 사용자 표시 이름
- `status`: `UserStatus` 사용자 상태
- `role`: `UserRole` 사용자 권한
- `authProvider`: `AuthProvider` 인증 제공자
- `providerUserId`: `ProviderUserId?` OAuth provider가 제공하는 사용자 식별자
- `registeredAt`: 가입 일시
- `lastLoginAt`: 마지막 로그인 일시
- `deactivatedAt`: 탈퇴 일시

#### 행위(Behaviors)

- `static register(email, nickname, authProvider, providerUserId, registeredAt)`: 사용자를 `ACTIVE` 상태로 등록한다
- `static restore(...)`: 저장소 snapshot을 도메인 객체로 복원한다
- `activate()`: 사용자를 활성화하고 탈퇴 일시를 지운다
- `deactivate(deactivatedAt)`: 사용자를 `DELETED` 상태로 전이하고 탈퇴 일시를 기록한다
- `recordLogin(loggedInAt)`: 로그인 성공 시각을 기록한다

#### 규칙(Rules)

- 이메일은 중복될 수 없다 (저장소 unique 제약으로 방어한다).
- `LOCAL` 사용자는 provider user id를 가질 수 없고, OAuth 사용자는 반드시 가져야 한다.
- 탈퇴(`DELETED`)한 사용자는 활성화할 수 없다.
- 이미 탈퇴한 사용자는 다시 탈퇴할 수 없다.
- 활성(`ACTIVE`) 사용자만 로그인 시각을 기록할 수 있다.
- 활성 사용자만 구독과 채널 바인딩을 등록·수정할 수 있다 (application 계층에서 강제한다).

### 사용자 상태(UserStatus)

_Enum_

- `ACTIVE`: 활성
- `INACTIVE`: 휴면
- `DELETED`: 탈퇴

### 사용자 권한(UserRole)

_Enum_

- `USER`: 일반 사용자
- `ADMIN`: 관리자

### 인증 제공자(AuthProvider)

_Enum_

- `LOCAL`, `GOOGLE`, `NAVER`, `KAKAO`

### 사용자 식별자(UserId)

_Value Object_

- `value`: 사용자 식별 UUID
- `newId()` / `of()`: 신규 생성(UUID v7) / 저장소 값 복원

### 이메일(Email)

_Value Object_

- `value`: 이메일 주소
- `of()`: trim 후 소문자로 정규화한다. 빈 값 불가, `@` 포함 필수, 255자 이하.

### 닉네임(Nickname)

_Value Object_

- `value`: 사용자 표시 이름
- `of()`: trim 정규화. 빈 값 불가, 100자 이하.

### OAuth 사용자 식별자(ProviderUserId)

_Value Object_

- `value`: OAuth provider가 발급한 사용자 식별 문자열
- `of()`: trim 정규화. 빈 값 불가, 255자 이하.

## 키워드 애그리거트

### 키워드(Keyword)

_Aggregate Root_

정규화 값 하나에 행 하나인 canonical 키워드다. 사용자와 무관하며, 사용자별 관심은 구독(`Subscription`)이 가진다.
같은 키워드를 여러 사용자가 등록해도 행은 하나라 수집·요약이 한 번이다.

#### 속성(Attributes)

- `id`: `KeywordId` 키워드 식별자. collector·ai가 쓰는 join key는 `canonicalKey`이고, 이 id는 이름 변경·병합이 필요해질 때를 위해 활성 키워드 API에 함께 싣는다
- `canonicalKey`: `CanonicalKey` 정규화 값 - Natural ID
- `displayName`: `KeywordName` 최초 등록 원문. 알림 본문처럼 사람에게 보이는 곳에 쓴다
- `createdAt`: 생성 일시

#### 행위(Behaviors)

- `static create(displayName, createdAt)`: 원문을 정규화해 canonical 키워드를 만든다
- `static restore(...)`: 저장소 snapshot을 복원한다

#### 규칙(Rules)

- `canonicalKey`당 하나다 (저장소 unique 제약, `utf8mb4_bin`으로 동등성을 코드의 정규화와 맞춘다).
- 이름 변경 행위가 없다. 이름이 다르면 다른 키워드다.

### 정규화 키(CanonicalKey)

_Value Object_

- `value`: 정규화된 키워드 문자열
- `of(raw)`: `KeywordNormalizer`로 정규화한다. NFKC -> trim -> 연속 공백 1개 -> `lowercase(Locale.ROOT)` -> 양끝 구두점(Unicode P* 카테고리) 제거.
  기호(`$tsla`의 `$`)와 문자열 중간의 구두점(`space-x`의 하이픈)은 남긴다. 결과가 비거나 100자를 넘으면 거부한다.
- `Tesla`, ` TESLA `, `Ｔｅｓｌａ`, `테슬라.`는 각각 `tesla`, `tesla`, `tesla`, `테슬라`가 된다.

### 키워드 이름(KeywordName)

_Value Object_

- `value`: 등록 원문
- `of()`: trim 정규화. 빈 값 불가, 100자 이하.

### 키워드 식별자(KeywordId)

_Value Object_

- `value`: 키워드 식별 UUID
- `newId()` / `of()`

## 구독 애그리거트

### 구독(Subscription)

_Aggregate Root_

사용자가 어떤 키워드를 어떤 채널로 받는지다. 사용자·키워드는 `UserId`·`KeywordId`로 참조한다.
불변이며 전이마다 새 인스턴스를 돌려준다.

#### 속성(Attributes)

- `id`: `SubscriptionId` 구독 식별자
- `userId`: `UserId` 구독한 사용자
- `keywordId`: `KeywordId` 구독한 canonical 키워드
- `channels`: `Set<SubscriptionChannel>` 받을 채널
- `enabled`: 수집·요약·알림 대상 포함 여부
- `registeredAt`: 등록 일시
- `disabledAt`: 비활성화 일시

#### 행위(Behaviors)

- `static create(userId, keywordId, channels, registeredAt)`: `enabled=true`로 등록한다
- `static restore(...)`: 저장소 snapshot을 복원한다
- `changeChannels(channels)`: 채널 집합을 바꾼다
- `enable()`: 대상에 다시 포함하고 비활성화 일시를 지운다
- `disable(disabledAt)`: 대상에서 제외하고 비활성화 일시를 기록한다

#### 규칙(Rules)

- 사용자·키워드당 하나다 (저장소 unique `(user_id, keyword_id)`). 같은 사용자가 `Tesla` 다음 `tesla`를 등록하면 중복이다.
- 채널은 하나 이상이어야 한다.
- 등록·변경 시점에 각 채널에 그 사용자의 `ACTIVE` 바인딩이 있어야 한다 (application 계층에서 강제한다).
  바인딩이 나중에 해지돼도 구독은 그대로 두고, 수신자 조회가 그 채널을 걸러낸다.
- 이미 비활성화된 구독은 다시 비활성화할 수 없다.
- 비활성 구독은 활성 키워드·수신자 조회에서 빠진다.

### 구독 채널(SubscriptionChannel)

_Enum_

- `SLACK`, `DISCORD`, `TELEGRAM`: 사용자가 고를 수 있는 채널만 둔다

### 구독 식별자(SubscriptionId)

_Value Object_

- `value`: 구독 식별 UUID
- `newId()` / `of()`

## 채널 바인딩 애그리거트

### 채널 바인딩(ChannelBinding)

_Aggregate Root_

사용자의 채널별 수신처다. 사용자·채널당 하나이며 재등록·해지·재연결은 모두 같은 행의 상태 전이라,
알림 쪽이 `(userId, channel)`로 가리키는 바인딩이 주소가 바뀌는 동안에도 같은 행이다.
불변이며 전이마다 새 인스턴스를 돌려준다.

```
createWithAddress ──▶ ACTIVE ◀── completeLink ── PENDING ◀── createPending / issueLinkToken
                       │  ▲                        │
                 revoke│  │bindAddress             │revoke
                       ▼  │                        ▼
                     REVOKED ◀─────────────────────┘
```

#### 속성(Attributes)

- `id`: `ChannelBindingId` 바인딩 식별자. 저장소 안에서만 쓰고 알림 쪽에는 나가지 않는다
- `userId`: `UserId` 소유 사용자
- `channel`: `SubscriptionChannel`
- `status`: `ChannelBindingStatus`
- `address`: `ChannelAddress?` 발송 주소. `ACTIVE`일 때만 있다
- `linkTokenHash`: `LinkTokenHash?` 연결 토큰 해시. `PENDING`일 때만 있다
- `linkTokenExpiresAt`: 연결 토큰 만료 시각. 해시와 함께 있다
- `createdAt`: 생성 일시
- `boundAt`: 마지막으로 주소가 연결된 일시
- `revokedAt`: 해지 일시

#### 행위(Behaviors)

- `static createWithAddress(userId, address, createdAt)`: 주소를 직접 받는 채널. 즉시 `ACTIVE`
- `static createPending(userId, channel, linkToken, createdAt)`: 연결 토큰으로 주소를 나중에 받는 채널. `PENDING`
- `static restore(...)`: 저장소 snapshot을 복원한다
- `bindAddress(address, boundAt)`: 주소를 교체하거나 해지된 바인딩을 되살린다. 어느 상태에서든 `ACTIVE`
- `issueLinkToken(linkToken)`: 새 토큰을 발급하고 `PENDING`으로 돌아간다. 이전 주소·토큰은 버린다
- `completeLink(address, boundAt)`: 토큰이 돌아왔다. `PENDING`이고 만료 전이어야 `ACTIVE`가 된다
- `isLinkTokenExpired(now)`: 토큰 만료 여부
- `revoke(revokedAt)`: 주소와 토큰을 지우고 `REVOKED`

#### 규칙(Rules)

- 사용자·채널당 하나다 (저장소 unique `(user_id, channel)`).
- 주소의 채널은 바인딩의 채널과 같아야 한다.
- `ACTIVE`일 때만 주소가 있고, `PENDING`일 때만 토큰 해시가 있다. 토큰 해시와 만료 시각은 함께 있어야 한다.
- 이미 해지된 바인딩은 다시 해지할 수 없다.
- 주소는 저장소에 AES-256-GCM 암호문으로만 남는다 (암호화는 persistence adapter의 책임이다).

### 바인딩 상태(ChannelBindingStatus)

_Enum_

- `PENDING`: 연결 토큰을 발급했고 주소가 아직 없다
- `ACTIVE`: 주소가 있고 발송할 수 있다
- `REVOKED`: 사용자가 해지했다. 주소는 지워져 있다

### 채널 주소(ChannelAddress)

_Value Object_

- `channel`: `SubscriptionChannel`
- `value`: 평문 주소. 원문은 이 프로퍼티로만 꺼낸다
- `of(channel, raw)`: trim 후 채널별로 검증한다. 빈 값 불가, 512자 이하.
  - `SLACK`: `https://hooks.slack.com/` 로 시작하는 webhook URL
  - `DISCORD`: `https://discord.com/api/webhooks/` 또는 `https://discordapp.com/api/webhooks/` 로 시작하는 webhook URL
  - `TELEGRAM`: 숫자 chat id (음수 허용)
- `masked()`: URL은 host까지, 그 외는 끝 두 글자만 남긴다. `toString()`도 마스킹한 값이라 로그에 원문이 남지 않는다.

### 연결 토큰(LinkToken)

_Value Object_

- `value`: 무작위 32바이트를 base64url로 표기한 토큰 원문
- `expiresAt`: 만료 시각
- `issue(now, ttl)`: 발급한다. ttl은 0보다 커야 한다
- `hash()`: 저장용 `LinkTokenHash`
- 원문은 사용자에게 연결 링크로 한 번만 돌려주고 저장하지 않는다.

### 연결 토큰 해시(LinkTokenHash)

_Value Object_

- `value`: 토큰 원문의 SHA-256 32바이트. 저장소에는 이것만 남는다
- `of(rawToken)` / `restore(bytes)`

### 바인딩 식별자(ChannelBindingId)

_Value Object_

- `value`: 바인딩 식별 UUID
- `newId()` / `of()`
