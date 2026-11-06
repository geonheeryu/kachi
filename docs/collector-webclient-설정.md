# collector-service WebClient 설정

## 목적

`collector-service`는 외부 뉴스 provider를 호출한다. 외부 API는 응답 지연, 연결 실패, rate limit, 일시 장애가 발생할 수 있으므로 provider별 WebClient 설정을 명시적으로 둔다.

설정은 다음 기준을 따른다.

- provider별 base URL과 endpoint path를 분리한다.
- provider별 WebClient bean을 분리한다.
- 여러 WebClient가 생기므로 `@Qualifier`로 주입 대상을 명시한다.
- timeout과 응답 크기 제한은 코드 기본값과 설정값으로 관리한다.
- 실제 운영 데이터가 쌓이면 provider별로 조정한다.

## 기본 설정

외부 provider WebClient는 다음 설정을 기본으로 갖는다.

| 설정 | 기본값 | 목적 | 조정 기준 |
| --- | --- | --- | --- |
| `connect-timeout` | `2s` | TCP 연결 지연을 빠르게 감지한다. | 연결 timeout이 잦으면 늘린다. |
| `response-timeout` | `5s` | provider 응답 대기 시간을 제한한다. | 정상 응답이 자주 5초를 넘으면 늘린다. |
| `read-timeout` | `5s` | 응답 body read 정지를 방어한다. | 큰 응답을 자주 받으면 늘린다. |
| `write-timeout` | `5s` | 요청 write 정지를 방어한다. | POST/큰 body 요청 provider에서 조정한다. |
| `max-in-memory-size` | `524288` | 응답 body 버퍼링 메모리를 제한한다. | XML/JSON 응답 크기 측정 후 조정한다. |

기본값은 보수적인 시작점이다. provider별 평균 응답 시간, timeout 빈도, 응답 크기가 쌓이면 조정한다.

## Bean 구성 기준

provider별 WebClient는 별도 bean으로 둔다.

| Bean | 역할 |
| --- | --- |
| `googleNewsWebClient` | Google News RSS 호출 전용 WebClient |
| `naverNewsWebClient` | Naver News API 호출 전용 WebClient |
| `finnhubNewsWebClient` | Finnhub News API 호출 전용 WebClient |

provider bean에는 `@Qualifier`로 주입 대상을 명시한다.

```kotlin
@Bean
fun googleNewsRssProvider(
    @Qualifier("googleNewsWebClient") googleNewsWebClient: WebClient,
    properties: GoogleNewsProperties,
): GoogleNewsRssProvider = GoogleNewsRssProvider(
    webClient = googleNewsWebClient,
    settings = GoogleNewsSettings(
        rssSearchPath = properties.rssSearchPath,
        languageCode = properties.languageCode,
        countryCode = properties.countryCode
    )
)
```

provider는 `GoogleNewsProperties`를 받지 않는다. config가 Properties를 풀어 어댑터 옆의 `GoogleNewsSettings`로 넘긴다.

파라미터 이름 기반 자동 매칭도 가능하지만, 여러 외부 client가 있는 서비스에서는 `@Qualifier`를 명시하는 편이 안전하다.

## 설정 구조

외부 provider 설정은 다음 prefix 아래에 둔다.

```yaml
kachi:
  collector:
    providers:
      {provider-name}:
```

provider 설정은 다음 구조를 따른다.

```yaml
kachi:
  collector:
    providers:
      google:
        enabled: true
        base-url: https://news.google.com
        rss-search-path: /rss/search
        language-code: ko
        country-code: KR
        connect-timeout: 2s
        response-timeout: 5s
        read-timeout: 5s
        write-timeout: 5s
        max-in-memory-size: 524288
      naver:
        enabled: false
        base-url: https://openapi.naver.com
        news-search-path: /v1/search/news.json
        client-id: ${NAVER_CLIENT_ID}
        client-secret: ${NAVER_CLIENT_SECRET}
        display: 100
        start: 1
        sort: date
      finnhub:
        enabled: false
        base-url: https://finnhub.io
        company-news-path: /api/v1/company-news
        api-key: ${FINNHUB_API_KEY}
        lookback-days: 7
```

## Google News RSS

### Provider 특성

| 항목 | 값 |
| --- | --- |
| 응답 형식 | RSS XML |
| 인증 | 없음 |
| endpoint | `/rss/search` |
| 검색어 query | `q` |
| 지역/언어 query | `hl`, `gl`, `ceid` |
| 장점 | API key 없이 수집 가능 |
| 한계 | 공식 안정 JSON API가 아니며 응답 구조나 접근 제한이 바뀔 수 있음 |
| URL 특성 | Google redirect URL이 내려올 수 있음 |

### Google 설정값

| 설정 | 값 | 근거 |
| --- | --- | --- |
| `enabled` | `true` | API key 없이 사용할 수 있어 기본으로 활성화한다. |
| `base-url` | `https://news.google.com` | provider host와 endpoint path를 분리한다. |
| `rss-search-path` | `/rss/search` | Google News RSS 검색 endpoint다. |
| `language-code` | `ko` | 서비스 타깃을 한국어 뉴스로 둔다. |
| `country-code` | `KR` | 서비스 타깃을 한국 뉴스 기준으로 둔다. |
| `connect-timeout` | `2s` | 공통 기본값을 따른다. |
| `response-timeout` | `5s` | 공통 기본값을 따른다. |
| `read-timeout` | `5s` | RSS XML body read 정지를 방어한다. |
| `write-timeout` | `5s` | 공통 기본값을 따른다. GET 요청이라 영향은 작다. |
| `max-in-memory-size` | `524288` | RSS XML body를 문자열로 받으므로 512KB 상한을 둔다. |

Google RSS 요청에서는 지역/언어 설정을 다음 query parameter로 사용한다.

```text
hl={language-code}
gl={country-code}
ceid={country-code}:{language-code}
```

원문 URL 추출, 제목 정규화, 응답 크기 제한 조정은 실제 RSS 데이터를 확인한 뒤 추가한다.

## Naver News Search

### Provider 특성

| 항목 | 값 |
| --- | --- |
| 응답 형식 | JSON |
| 인증 | `X-Naver-Client-Id`, `X-Naver-Client-Secret` |
| endpoint | `/v1/search/news.json` |
| 검색어 query | `query` |
| 페이지 query | `display`, `start` |
| 정렬 query | `sort=sim` 또는 `sort=date` |
| URL 특성 | `originallink`와 `link`가 함께 내려올 수 있음 |

### Naver 설정값

| 설정 | 값 | 근거 |
| --- | --- | --- |
| `enabled` | `false` | credential이 필요하므로 명시적으로 켠 경우에만 사용한다. |
| `base-url` | `https://openapi.naver.com` | provider host와 endpoint path를 분리한다. |
| `news-search-path` | `/v1/search/news.json` | Naver Search API 뉴스 검색 endpoint다. |
| `client-id` | `${NAVER_CLIENT_ID}` | Naver API 인증 header에 사용한다. |
| `client-secret` | `${NAVER_CLIENT_SECRET}` | Naver API 인증 header에 사용한다. |
| `display` | `100` | Naver Search API의 최대 page size를 사용한다. |
| `start` | `1` | 첫 페이지부터 수집한다. |
| `sort` | `date` | 최신 뉴스 수집을 우선한다. |
| `max-in-memory-size` | `524288` | JSON body 역직렬화에 512KB 상한을 둔다. |

Naver 응답 변환 기준:

- `title`은 HTML entity unescape 후 HTML tag를 제거한다.
- URL은 `originallink`를 우선 사용하고, 없으면 `link`를 사용한다.
- `pubDate`는 RFC 1123 형식으로 파싱하고, 실패하면 `publishedAt=null`로 둔다.

## Finnhub Company News

### Provider 특성

| 항목 | 값 |
| --- | --- |
| 응답 형식 | JSON array |
| 인증 | `X-Finnhub-Token` |
| endpoint | `/api/v1/company-news` |
| symbol query | `symbol` |
| 기간 query | `from`, `to` |
| 날짜 형식 | `YYYY-MM-DD` |
| 시간 필드 | `datetime` UNIX timestamp seconds |

### Finnhub 설정값

| 설정 | 값 | 근거 |
| --- | --- | --- |
| `enabled` | `false` | API key가 필요하므로 명시적으로 켠 경우에만 사용한다. |
| `base-url` | `https://finnhub.io` | provider host와 endpoint path를 분리한다. |
| `company-news-path` | `/api/v1/company-news` | Finnhub Company News endpoint다. |
| `api-key` | `${FINNHUB_API_KEY}` | Finnhub API 인증 header에 사용한다. |
| `lookback-days` | `7` | 최근 7일 회사 뉴스를 조회한다. |
| `max-in-memory-size` | `524288` | JSON body 역직렬화에 512KB 상한을 둔다. |

Finnhub 응답 변환 기준:

- collector keyword를 company symbol로 보고 대문자로 정규화한다.
- `from`은 오늘 기준 `lookback-days` 전 날짜, `to`는 오늘 날짜로 요청한다.
- `headline`을 제목으로 사용한다.
- `url`을 원문 URL로 사용한다.
- `datetime`은 UNIX timestamp seconds로 파싱하고, 없거나 epoch 이전이면 `publishedAt=null`로 둔다.

## 조정 기준

다음 데이터가 쌓이면 설정값을 조정한다.

| 데이터 | 조정 대상 |
| --- | --- |
| provider별 평균 응답 시간 | `response-timeout` |
| 연결 timeout 발생 빈도 | `connect-timeout` |
| body read timeout 발생 빈도 | `read-timeout` |
| HTTP 429 발생 빈도 | rate limit, scheduler interval |
| 5xx 발생 빈도 | retry, 서킷 브레이커 |
| RSS XML 평균/최대 크기 | `max-in-memory-size` |
| 수집 실행 1회 전체 소요 시간 | provider timeout, 병렬도 |

provider 오류 분류를 추가하면 timeout, 429, 5xx, XML 파싱 실패를 `ProviderFailureReason`으로 변환한다.
