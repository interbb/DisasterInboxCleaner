# 광고 문자 자동 삭제 설계 (DisasterInboxCleaner → "문자함 정리")

- 작성일: 2026-09-15
- 대상 프로젝트: `workspace/private/intellij/DisasterInboxCleaner` (개인용 사이드로드 앱, Google Play 미배포)
- 기준 기기: Galaxy Fold8 SM-F971N, Android 17 / One UI 9.0, 기본 문자 앱 = 삼성 메시지
- 상태: 설계 승인 완료(3개 섹션), 구현 계획 작성 전

## 1. 배경과 확정된 사실

2026-09-15 실기기 스파이크로 다음이 확인됐다.

- 기본 문자 앱이 아닌 앱도 `READ_SMS` 승인 + `appops WRITE_SMS allow` 상태에서 `content://sms`(`Telephony.Sms.CONTENT_URI`) + selection 방식으로 문자함 행을 삭제할 수 있다. `content://sms/inbox` 계열 URI는 삭제 요청 시 `IllegalArgumentException: Unknown URL`이며, `content://sms/N`은 where절을 무시한다.
- 비기본 앱의 조회는 받은·보낸 문자만 보이는 제한 뷰이고, 이 기기는 그 위에 `#CMAS#` 행을 추가로 숨긴다. **일반 발신번호의 광고 문자 행은 비기본 앱에도 보인다.** 따라서 광고 문자는 목록 표시와 삭제가 모두 가능하다.
- 기본 문자 앱 역할 교체나 Shizuku는 필요 없다.
- 1.0.3에서 재난문자 삭제 URI 결함을 수정했고 폰에서 삭제를 실측했다. 재난문자 경로(알림·팝업·경고음 유지, 문자함 복사본만 삭제, 본문 미조회)는 이 설계에서 바꾸지 않는다.

## 2. 목표와 비목표

목표:
- 수신 직후 본문이 `(광고)` 또는 `[광고]`로 시작하는 문자(정보통신망법 의무 표기)를 삼성 메시지함에서 자동 삭제한다.
- 삭제된 광고 문자의 삼성 메시지 알림을 최선 노력으로 닫는다.
- 삭제 이력(시각·발신번호·본문 앞 30자·규칙)을 앱 안에서 목록으로 보여준다.
- 기능을 켜기 전에 쌓인 광고 문자를 목록으로 보고 선택·전체 삭제한다.
- 앱 이름을 "문자함 정리"로 일반화한다.

비목표(v1 범위 밖, §10 참고):
- 표기 없는 스팸, MMS·RCS 광고, 삼성 스팸함으로 이미 이동한 문자.
- 규칙 편집 화면, 발신번호 화이트리스트, 격리함·되돌리기.
- 수신음·진동·잠깐 뜨는 알림 억제(비기본 앱은 불가).
- 재난문자 경로의 변경.

## 3. 사용자 결정 기록

| 항목 | 결정 |
|---|---|
| 구조 | DisasterInboxCleaner 같은 앱에 나란히 추가, 패키지명 유지 |
| 처리 방식 | 즉시 삭제 + 이력 기록(격리·되돌리기 없음) |
| 이력 범위 | 발신번호·시각·규칙 + 본문 앞 30자, 앱 내부 저장, 300건·30일 상한 |
| 판정 규칙 | 법정 표기만: 본문이 `(광고)` 또는 `[광고]`로 시작 |
| 광고 알림 | 행 삭제 성공 시 삼성 메시지 알림도 닫기(재난문자 알림은 그대로) |
| 접근 | 하이브리드: ContentObserver 감지·삭제가 진실 소스, 알림 리스너는 재시도 트리거와 알림 닫기 |
| 기존 광고 정리 | v1 포함(목록·선택·전체 삭제) |
| 앱 이름 | "문자함 정리" |
| 권한 요청 | 앱 내 `READ_SMS` 요청은 이미 구현돼 있음(온보딩 "문자 권한 요청" 버튼, `MainActivity`의 권한 런처). 변경 없음 |

## 4. 구조와 컴포넌트

기존 파일은 재난문자 로직을 건드리지 않는 범위에서만 수정한다. 새 파일 5개(코드 4 + 화면 1), 수정 파일 9개, 새 테스트 파일 2개.

### 4.1 새 파일

**`AdSmsPolicy.kt`** (object, 순수 문자열·함수, JVM 단위 테스트 대상)

```kotlin
object AdSmsPolicy {
    const val RULE_LEGAL_MARK = "legal_mark"   // 자동 삭제
    const val RULE_MANUAL = "manual"           // 수동 삭제
    const val SNIPPET_LENGTH = 30
    val markSelection: String                  // "(body LIKE '(광고)%' OR body LIKE '[광고]%')"
    val inboxAdSelection: String               // "type = 1 AND $markSelection"  (수동 목록·전체 삭제)
    val autoDeleteSelection: String            // "$inboxAdSelection AND date >= ?"
    fun autoDeleteSelectionArgs(enabledAt: Long): Array<String>
    val byIdSelection: String                  // "_id = ? AND $inboxAdSelection"
    fun byIdSelectionArgs(id: Long): Array<String>
    fun isAd(body: String?): Boolean           // 공백 제거 없이 "(광고)" 또는 "[광고]"로 시작
    fun snippet(body: String?): String         // 앞 30자, 개행·탭은 공백으로, 앞뒤 공백 제거
}
```

- `isAd`와 SQL selection은 같은 "엄격한 접두" 의미를 갖는다. 앞 공백이나 전각 괄호 `（광고）`는 v1에서 다루지 않는다.
- `type = 1`은 `Telephony.Sms.MESSAGE_TYPE_INBOX`를 리터럴로 둔다(단위 테스트에 Android 런타임 불필요). `EmergencyCopyPolicy`와 같은 방식.

**`AdInboxCleaner.kt`** (Context, AdCleanupHistory)

```kotlin
data class AdCandidate(val id: Long, val address: String, val receivedAt: Long, val body: String)

sealed interface AdCleanupResult {
    data class Success(val deleted: Int, val addresses: List<String>) : AdCleanupResult
    data class Failure(val error: MonitorError) : AdCleanupResult
}

class AdInboxCleaner(private val context: Context, private val history: AdCleanupHistory) {
    fun cleanSince(enabledAt: Long): AdCleanupResult          // 자동 경로
    fun listCandidates(): HistoryLoadResult                   // 수동 목록 (DisasterMessageItem 재사용, address 필드 추가)
    fun deleteByIds(ids: Set<Long>): ManualDeleteResult       // 수동 선택 삭제
    fun deleteAll(): ManualDeleteResult                       // 수동 전체 삭제
}
```

- 모든 조회는 `Telephony.Sms.CONTENT_URI`에 projection `_id, address, date, body`, 정렬 `date DESC`.
- 모든 삭제는 `Telephony.Sms.CONTENT_URI` + `byIdSelection` 행별 호출. 반환값이 1인 행만 이력에 기록한다.
- `cleanSince`: 권한 검사(`PermissionState.canReadSms`, `canDeleteSms`) → `autoDeleteSelection`으로 후보 조회 → 행별 삭제 → 성공 행을 `history.append`(규칙 `legal_mark`) → `Success(deleted, addresses)`. `enabledAt <= 0`이면 `Success(0, [])`.
- `deleteByIds`·`deleteAll`: 후보 조회 후 행별 삭제, 성공 행은 규칙 `manual`로 이력 기록.
- 예외 매핑은 재난문자와 동일: `SecurityException` → `SMS_DELETE_PERMISSION_REQUIRED`(조회 단계는 `SMS_READ_PERMISSION_REQUIRED`), 그 외 `RuntimeException` → `SMS_PROVIDER_REJECTED`.

**`AdCleanupHistory.kt`**

```kotlin
data class AdCleanupEntry(val at: Long, val address: String, val snippet: String, val rule: String)

class AdCleanupHistory(private val file: File) {
    @Synchronized fun append(entries: List<AdCleanupEntry>)
    @Synchronized fun list(): List<AdCleanupEntry>      // 최신순
    @Synchronized fun clear()
    companion object {
        const val MAX_ENTRIES = 300
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        fun default(context: Context) = AdCleanupHistory(File(context.filesDir, "ad_cleanup_history.tsv"))
    }
}
```

- 파일 형식: 탭 구분 텍스트(`ad_cleanup_history.tsv`). 첫 줄 `v1`, 이후 한 줄에 한 항목 `at<TAB>address<TAB>rule<TAB>snippet`. 탭·개행은 저장 전에 공백으로 치환. `org.json`은 JVM 단위 테스트에서 스텁이라 쓰지 않는다. 의존성 추가 없음.
- `append` 시 30일 지난 항목 제거 후 최신 300건만 유지. 파일 파싱 실패 시 기존 파일을 `ad_cleanup_history.tsv.bak`으로 옮기고 빈 이력으로 시작.
- 생성자에 `File`을 주입하므로 단위 테스트는 임시 디렉토리에서 돌린다.

**`AdMonitorPreferences.kt`** (prefs 이름 `ad_monitor_state`, 재난문자용 `MonitorPreferences`와 별개)

```kotlin
data class StoredAdMonitorState(
    val enabled: Boolean, val enabledAt: Long, val deletedCount: Long,
    val lastDeletedAt: Long, val lastError: MonitorError?,
)
class AdMonitorPreferences(context: Context) {
    fun snapshot(): StoredAdMonitorState
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)      // 켤 때 enabledAt = now, lastError 제거
    fun recordCleanup(deleted: Int)       // @Synchronized, 누계·마지막 시각 갱신, lastError 제거
    fun recordReady()
    fun recordError(error: MonitorError)
    fun registerListener(...) / unregisterListener(...)
}
```

### 4.2 수정 파일

**`DisasterNotificationListenerService.kt`**
- 두 번째 `ContentObserver`를 `Telephony.Sms.CONTENT_URI`(`content://sms`, notifyForDescendants = true)에 등록. 광고 기능이 켜져 있으면 300ms 뒤 `performAdCleanup` 예약. 기존 재난문자 관찰자(`sms/inbox`)는 그대로 둔다.
- `onNotificationPosted`: 패키지가 `com.samsung.android.messaging`이고 광고 기능이 켜져 있으면 `RETRY_DELAYS_MS`(400/1500/4000ms)로 `performAdCleanup` 예약. 재난문자 분기는 변경 없음.
- `performAdCleanup()`: `AdInboxCleaner.cleanSince(adPrefs.snapshot().enabledAt)` → 결과를 `AdMonitorPreferences`에 기록 → `deleted > 0`이면 `cancelAdNotifications()`.
- `cancelAdNotifications()`: `activeNotifications` 중 패키지가 삼성 메시지이고 `extras`의 `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, `EXTRA_TEXT_LINES` 중 하나가 `AdSmsPolicy.isAd`를 만족하면 `cancelNotification(key)`. 텍스트가 없거나 읽을 수 없으면 건너뛴다.
- 워커는 기존 단일 스레드 `ScheduledExecutorService`를 공유한다(재난문자 정리와 순차 실행).
- 구현 시 확인: 매니페스트의 `android.service.notification.default_filter_types` 메타데이터가 삼성 메시지 알림을 리스너에 전달하는지. 막히면 값을 조정한다.

**`MainViewModel.kt`**
- `MonitorUiState`에 `ad: AdUiState(enabled, deletedCount, lastDeletedAt, lastError)` 추가. `setAdEnabled(Boolean)` 추가.
- `HistorySource { DISASTER, AD }` 도입. `loadHistory(source)`, `deleteSelectedHistory()`, `deleteAllHistory()`가 현재 source에 따라 `DisasterMessageRepository` 또는 `AdInboxCleaner`를 호출. `HistoryUiState`에 `source` 필드 추가.
- 광고 삭제 이력용 `adLogState: StateFlow<List<AdCleanupEntry>>`, `loadAdLog()`, `clearAdLog()` 추가.
- `DisasterMessageItem`에 `address: String = ""` 추가(재난문자 경로는 기본값).

**`ui/HomeScreen.kt`**
- 제목 "문자함 정리".
- 재난문자 카드 아래 두 번째 `FeatureSwitchCard` "광고 문자 자동 삭제". 설명: "본문이 (광고)/[광고]로 시작하는 문자를 수신 즉시 삭제하고 알림을 닫습니다."
- 상태 카드에 "삭제한 광고: N개", "마지막 광고 삭제: 시각", 광고 오류 줄(있을 때).
- 버튼 "기존 광고 문자 정리"(HISTORY 화면, source = AD), "광고 삭제 이력"(AD_LOG 화면).

**`ui/HistoryScreen.kt`**
- `source` 매개변수로 제목·문구 분기: 재난문자는 현행 문구, 광고는 "기존 광고 문자 정리", "메시지함의 광고 문자 모두 삭제", 빈 목록 문구 "광고 문자가 없습니다." 광고 목록 행에는 발신번호도 표시.
- "개별 목록 제한됨" 카드는 재난문자 source에서만 표시.

**`ui/AdLogScreen.kt`** (새 파일, 단순 목록)
- 항목: 시각, 발신번호, 앞 30자, 규칙(자동/수동). 상단 "이력 지우기" 버튼(확인창 1회). 항목 탭 동작 없음.

**`MainActivity.kt`**
- `AppScreen`에 `AD_LOG` 추가, HISTORY 진입 시 source 전달, 뒤로가기 처리에 AD_LOG 포함.
- 앱 내 `READ_SMS` 요청 런처는 이미 있다(`smsPermissionLauncher`). 변경 없음.

**`ui/OnboardingScreen.kt`**
- "문자 권한 요청" 버튼은 이미 있다. 안내 문구에 광고 기능 한 줄 추가, 문자 읽기 설명을 "재난문자 감시는 본문을 조회하지 않고 광고 판정은 본문 앞부분만 확인"으로 수정.

**`AndroidManifest.xml`, `app/build.gradle.kts`, `README.md`**
- `android:label` "문자함 정리". `versionCode 5`, `versionName "1.1.0"`.
- README: 제목·개요를 "재난문자 복사본 + 광고 문자" 두 기능으로. 프라이버시 원칙 갱신: 광고 판정을 위해 자동 경로가 본문을 읽고 앞 30자를 기기 내부 파일에 남긴다, 재난문자 경로는 본문을 읽지 않는다. §9 한계 명시.

## 5. 데이터 흐름

1. 문자 수신 → 삼성 메시지(기본 앱)가 `content://sms` 행 삽입, 알림 게시.
2. 트리거 (둘 중 먼저 오는 쪽, 겹쳐도 무방):
   - `content://sms` ContentObserver → 300ms 뒤 `performAdCleanup`
   - 삼성 메시지 알림 → 400/1500/4000ms 뒤 `performAdCleanup`
3. `AdInboxCleaner.cleanSince(enabledAt)`: `autoDeleteSelection`으로 후보 조회 → 행별 삭제 → 성공 행 이력 기록 → 누계 갱신.
4. `deleted > 0`이면 `cancelAdNotifications()`.
5. 홈 화면은 prefs 변경 리스너로 상태를 갱신한다(재난문자와 동일한 방식).

멱등성: 삭제된 행은 다음 트리거의 조회에 나타나지 않으므로 중복 삭제·중복 이력이 없다. 삭제 실패 행은 다음 트리거에서 재시도되며 별도 폴링은 없다.

## 6. 권한과 온보딩

- 필요한 권한은 현행 3종 그대로: 알림 접근(설정 토글), `READ_SMS`(런타임 권한), `WRITE_SMS` appops(adb 전용, 설치당 1회, 재부팅 유지, 재설치 시 재부여).
- `READ_SMS`는 온보딩의 "문자 권한 요청" 버튼(이미 구현)으로 앱 내 대화상자에서 요청한다. adb로 설치한 앱은 대화상자로 승인될 것으로 예상하며 E2E에서 확인한다. 파일 관리자 설치본은 Android 15부터 "제한된 설정 허용" 절차가 필요할 수 있고, 그 경우 현행 adb 안내(`pm grant`)가 폴백이다.
- 설정 화면의 adb 명령 안내는 변경 없음.

## 7. 오류 처리

- 권한 부재: `AdInboxCleaner`가 `Failure(MonitorError)`를 반환하고 `AdMonitorPreferences.lastError`에 기록. 홈 카드에 재난문자와 같은 문구 형식으로 표시.
- provider 예외: `SecurityException` → 삭제(또는 읽기) 권한 필요, 그 외 `RuntimeException` → `SMS_PROVIDER_REJECTED`.
- 행별 독립 삭제. 반환 1인 행만 이력에 남긴다.
- 알림 닫기는 삭제 1건 이상일 때만 수행하고, 텍스트를 읽을 수 없는 알림은 조용히 건너뛴다.
- 이력 파일 손상: `.bak`으로 옮기고 빈 이력으로 시작.
- 켠 시각 판정은 `date` 컬럼(기본 앱이 기록한 수신 시각) 기준. 켜기 전 문자는 자동 경로 대상이 아니며 "기존 광고 문자 정리"로 처리한다.

## 8. 테스트

JVM 단위 테스트(Android 런타임 불필요):
- `AdSmsPolicyTest`: 세 selection 문자열에 `type = 1`·표기 두 형태·`date >= ?`/`_id = ?` 포함 여부, `isAd`에 대해 `(광고)…`·`[광고]…` 참, `" (광고)…"`·`인증번호…`·`[Web발신]…`·빈 문자열·null 거짓, `snippet`의 30자 절단·개행 치환·공백 제거.
- `AdCleanupHistoryTest`: 임시 파일로 append→list 최신순, 301번째 추가 시 300건 유지, 31일 지난 항목 제거, 손상 파일 `.bak` 이동 후 빈 목록, `clear`, 탭·개행 치환 후 왕복.
- 기존 `EmergencyCopyPolicyTest`·`HistoryUiStateTest` 회귀 통과.
- `AdMonitorPreferences`·ViewModel·서비스는 Android 의존이라 단위 테스트 제외(Robolectric 미도입). 실기기로 검증.

빌드·설치:
```bash
GRADLE_USER_HOME="$PWD/.gradle-user" ANDROID_USER_HOME="$PWD/.android-user" ./gradlew --offline testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.interbb.disasterinboxcleaner android.permission.READ_SMS   # 앱 내 요청이 안 될 때만
adb shell appops set com.interbb.disasterinboxcleaner WRITE_SMS allow
```

실기기 E2E(사용자 조작 + adb 셸 확인):
1. 앱에서 "광고 문자 자동 삭제" 켜기.
2. 다른 폰이나 웹문자로 `(광고) 테스트 문자` 를 Fold8에 발송.
3. 기대: 삼성 메시지함에 남지 않음, 알림이 닫힘, "광고 삭제 이력"에 시각·발신번호·"(광고) 테스트 문자"·자동 규칙 1건. adb 셸 `content query --uri content://sms/inbox --where "body LIKE '(광고)%'"` 결과 0건.
4. "기존 광고 문자 정리": 기능을 끈 상태에서 같은 문자를 한 번 더 받아 목록에 표시되는지, 전체 삭제 후 사라지는지 확인. 이력에 수동 규칙으로 기록.
5. 재난문자 경로 회귀: 홈 카드 상태와 설정 화면이 그대로인지, 다음 재난문자 수신 시 "마지막 정리" 카운트가 오르는지.

## 9. 한계 (README에 명시)

- 수신음·진동과 잠깐 뜨는 알림은 비기본 앱이라 막을 수 없다.
- `(광고)`/`[광고]` 표기 없는 스팸, MMS·RCS 광고, 삼성 스팸함으로 이미 이동한 문자는 대상이 아니다.
- 재설치하면 `WRITE_SMS` appops를 다시 부여해야 한다.
- 알림 패키지 상수는 삼성 메시지 하나다. 기본 문자 앱이 구글 메시지로 바뀌면 상수 한 곳(`com.google.android.apps.messaging`)을 바꿔야 한다.
- 이력에는 본문 앞 30자만 남는다. 전체 본문은 어디에도 저장하지 않는다.

## 10. 범위 밖 후속 후보

- 재난문자 관찰자 URI: 현재 `sms/inbox`에 등록돼 있어 `content://sms`로 삽입되는 변경 통지를 놓칠 수 있다. 다음 재난문자 수신 때 "마지막 정리" 카운트로 확인하고 필요하면 `content://sms`로 옮긴다.
- 재난문자 목록 표시: `content://sms` 경로 조회로 `#CMAS#` 행이 보이는지 다음 재난문자 때 실측. 안 되면 알림 리스너 수신 시점 기록 방식 검토.
- 전각 괄호 `（광고）` 등 표기 변형, 발신번호 화이트리스트, MMS 광고.
- Shizuku로 폰 단독 appops 부여(편의).
- 구글 메시지 알림 패키지 병행 지원.
