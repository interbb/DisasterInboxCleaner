# 광고 문자 자동 삭제 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DisasterInboxCleaner에 "본문이 (광고)/[광고]로 시작하는 문자를 수신 즉시 삭제하고, 알림을 닫고, 이력을 남기며, 기존 광고 문자를 목록에서 정리"하는 기능을 추가하고 앱 이름을 "문자함 정리"로 바꾼다.

**Architecture:** 재난문자 경로와 같은 모양으로 광고 경로를 나란히 추가한다. 순수 정책 객체(`AdSmsPolicy`) → 문자함 조회·삭제(`AdInboxCleaner`) → 로컬 TSV 이력(`AdCleanupHistory`) → 별도 prefs(`AdMonitorPreferences`)를 두고, 기존 `DisasterNotificationListenerService`가 `content://sms` 관찰자와 삼성 메시지 알림을 트리거로 광고 정리를 예약한다. 삭제는 반드시 `Telephony.Sms.CONTENT_URI` + selection으로 한다(`sms/inbox` URI는 provider가 거부).

**Tech Stack:** Kotlin, Jetpack Compose + Material3, AGP 9.1.1 내장 Kotlin, Gradle 9.3.1, JUnit 4.13.2(JVM 단위 테스트), adb. 의존성 추가 없음.

**Spec:** `docs/superpowers/specs/2026-09-15-ad-sms-cleanup-design.md`

## Global Constraints

- 프로젝트 루트: `/Users/interbb/workspace/private/intellij/DisasterInboxCleaner`. git 저장소가 아니다(Task 0 참고).
- 빌드 환경(모든 gradle 명령 앞에 붙인다):
  ```bash
  cd /Users/interbb/workspace/private/intellij/DisasterInboxCleaner && export JAVA_HOME=/Users/interbb/Library/Java/JavaVirtualMachines/ms-17.0.15/Contents/Home GRADLE_USER_HOME="$PWD/.gradle-user" ANDROID_USER_HOME="$PWD/.android-user"
  ```
  gradle은 항상 `--offline`으로 실행한다(의존성 추가 금지, 캐시만 사용).
- 패키지명·applicationId `com.interbb.disasterinboxcleaner` 유지. compileSdk/targetSdk 37, minSdk 29.
- 삭제 URI는 `Telephony.Sms.CONTENT_URI`만 사용. `Telephony.Sms.Inbox.CONTENT_URI`는 조회에만 쓴다.
- 광고 판정은 본문이 `(광고)` 또는 `[광고]`로 **시작**하는 경우만(공백·전각 변형 없음). SQL과 Kotlin 판정이 같은 의미여야 한다.
- 이력에는 시각·발신번호·규칙·본문 앞 30자만 남긴다. 전체 본문은 어디에도 저장하지 않는다.
- 재난문자 경로(`EmergencyCopyPolicy`, `EmergencyInboxCleaner`, `DisasterMessageRepository`의 로직, `MonitorPreferences`)는 변경하지 않는다. `DisasterMessageItem`에 `address` 필드 추가만 허용.
- 앱 이름 "문자함 정리", 버전 `versionCode 5`, `versionName "1.1.0"`.
- 문구는 한국어, 기존 화면의 어조를 따른다.
- 새 파일에 `TODO`·`FIXME`·미구현 분기 금지.
- **git 사용 안 함(2026-09-15 사용자 결정)**: Task 0은 건너뛴다. 각 태스크의 "Commit" 단계는 실행하지 말고, 그 직전 단계의 빌드·테스트 통과를 완료 조건으로 삼는다. `git` 명령을 실행하지 않는다.
- Shizuku 자가 권한 부여는 v1 완료 후 Task 11로 별도 추가한다(이 계획에 없음).

---

### Task 0: (선택) git 저장소 초기화

프로젝트에 `.gitignore`는 있지만 `.git`이 없다. 이후 태스크의 "Commit" 단계를 쓰려면 초기화한다. 사용자가 원치 않으면 이 태스크를 건너뛰고, 각 태스크의 Commit 단계는 "빌드·테스트 통과 확인"으로 대신한다.

**Files:**
- Create: `.git/` (git init)

- [ ] **Step 1: 초기화와 기준 커밋**

```bash
cd /Users/interbb/workspace/private/intellij/DisasterInboxCleaner
git init -q
git add -A
git commit -q -m "chore: baseline 1.0.3 (delete URI fix, spec and plan)"
git log --oneline | head -1
```

Expected: 커밋 해시 한 줄. `.android-sdk/`, `.gradle-user/`, `build/`는 `.gitignore`로 제외된다.

---

### Task 1: AdSmsPolicy (판정 규칙과 selection 문자열)

**Files:**
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/AdSmsPolicy.kt`
- Test: `app/src/test/java/com/interbb/disasterinboxcleaner/AdSmsPolicyTest.kt`

**Interfaces:**
- Consumes: 없음(순수 Kotlin).
- Produces:
  - `AdSmsPolicy.RULE_LEGAL_MARK: String = "legal_mark"`, `RULE_MANUAL = "manual"`, `SNIPPET_LENGTH = 30`
  - `AdSmsPolicy.markSelection`, `inboxAdSelection`, `autoDeleteSelection`, `byIdSelection: String`
  - `AdSmsPolicy.autoDeleteSelectionArgs(enabledAt: Long): Array<String>`, `byIdSelectionArgs(id: Long): Array<String>`
  - `AdSmsPolicy.isAd(body: String?): Boolean`, `AdSmsPolicy.snippet(body: String?): String`

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/java/com/interbb/disasterinboxcleaner/AdSmsPolicyTest.kt`:

```kotlin
package com.interbb.disasterinboxcleaner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSmsPolicyTest {
    @Test
    fun selectionsPinInboxTypeAndBothMarkers() {
        assertEquals("(body LIKE '(광고)%' OR body LIKE '[광고]%')", AdSmsPolicy.markSelection)
        assertEquals(
            "type = 1 AND (body LIKE '(광고)%' OR body LIKE '[광고]%')",
            AdSmsPolicy.inboxAdSelection,
        )
        assertEquals(
            "type = 1 AND (body LIKE '(광고)%' OR body LIKE '[광고]%') AND date >= ?",
            AdSmsPolicy.autoDeleteSelection,
        )
        assertEquals(
            "_id = ? AND type = 1 AND (body LIKE '(광고)%' OR body LIKE '[광고]%')",
            AdSmsPolicy.byIdSelection,
        )
        assertArrayEquals(arrayOf("1700000000000"), AdSmsPolicy.autoDeleteSelectionArgs(1_700_000_000_000L))
        assertArrayEquals(arrayOf("42"), AdSmsPolicy.byIdSelectionArgs(42L))
    }

    @Test
    fun isAdRequiresStrictLegalPrefix() {
        assertTrue(AdSmsPolicy.isAd("(광고)여름 세일 안내"))
        assertTrue(AdSmsPolicy.isAd("[광고] 이벤트 안내"))
        assertFalse(AdSmsPolicy.isAd(" (광고)앞 공백"))
        assertFalse(AdSmsPolicy.isAd("[Web발신] (광고)중간 표기"))
        assertFalse(AdSmsPolicy.isAd("인증번호 [123456]를 입력하세요"))
        assertFalse(AdSmsPolicy.isAd(""))
        assertFalse(AdSmsPolicy.isAd(null))
    }

    @Test
    fun snippetKeepsThirtyCharsOnOneLine() {
        val body = "(광고)줄1\n줄2\t탭 " + "가".repeat(40)
        val snippet = AdSmsPolicy.snippet(body)
        assertEquals(30, snippet.length)
        assertTrue(snippet.startsWith("(광고)줄1 줄2 탭 "))
        assertFalse(snippet.contains('\n'))
        assertFalse(snippet.contains('\t'))
        assertEquals("짧은 본문", AdSmsPolicy.snippet("  짧은 본문  "))
        assertEquals("", AdSmsPolicy.snippet(null))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
./gradlew --offline testDebugUnitTest --tests 'com.interbb.disasterinboxcleaner.AdSmsPolicyTest' 2>&1 | grep -E "^e: |BUILD" | head
```
Expected: `e: … Unresolved reference 'AdSmsPolicy'` 와 `BUILD FAILED`.

- [ ] **Step 3: 구현**

`app/src/main/java/com/interbb/disasterinboxcleaner/AdSmsPolicy.kt`:

```kotlin
package com.interbb.disasterinboxcleaner

/**
 * Identifies legally marked advertising SMS: the body starts with "(광고)" or "[광고]".
 *
 * Selections target Telephony.Sms.CONTENT_URI (content://sms), which spans every box, so each
 * one pins the inbox type. The Kotlin predicate [isAd] and the SQL LIKE prefixes mean the same
 * thing: a strict prefix, no whitespace trimming, no full-width variants.
 */
object AdSmsPolicy {
    const val RULE_LEGAL_MARK = "legal_mark"
    const val RULE_MANUAL = "manual"
    const val SNIPPET_LENGTH = 30

    private const val BODY_COLUMN = "body"
    private const val TYPE_COLUMN = "type"
    private const val DATE_COLUMN = "date"
    private const val ID_COLUMN = "_id"

    /** Telephony.Sms.MESSAGE_TYPE_INBOX, kept literal so unit tests need no Android runtime. */
    private const val INBOX_TYPE = 1

    val markers: List<String> = listOf("(광고)", "[광고]")

    val markSelection: String = markers.joinToString(prefix = "(", separator = " OR ", postfix = ")") {
        "$BODY_COLUMN LIKE '$it%'"
    }

    /** Manual list and "delete all": every inbox row that carries the legal mark. */
    val inboxAdSelection: String = "$TYPE_COLUMN = $INBOX_TYPE AND $markSelection"

    /** Automatic cleanup: marked inbox rows received after the feature was enabled. */
    val autoDeleteSelection: String = "$inboxAdSelection AND $DATE_COLUMN >= ?"

    fun autoDeleteSelectionArgs(enabledAt: Long): Array<String> = arrayOf(enabledAt.toString())

    /** Per-row delete, still guarded by type and mark so a stale id can never delete another row. */
    val byIdSelection: String = "$ID_COLUMN = ? AND $inboxAdSelection"

    fun byIdSelectionArgs(id: Long): Array<String> = arrayOf(id.toString())

    fun isAd(body: String?): Boolean = body != null && markers.any { body.startsWith(it) }

    fun snippet(body: String?): String {
        if (body == null) return ""
        val flat = body.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
        return if (flat.length <= SNIPPET_LENGTH) flat else flat.substring(0, SNIPPET_LENGTH)
    }
}
```

- [ ] **Step 4: 통과 확인**

Run:
```bash
./gradlew --offline testDebugUnitTest --tests 'com.interbb.disasterinboxcleaner.AdSmsPolicyTest' 2>&1 | grep -E "^e: |FAILED|BUILD" | head
```
Expected: `BUILD SUCCESSFUL`, `FAILED` 없음.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/interbb/disasterinboxcleaner/AdSmsPolicy.kt app/src/test/java/com/interbb/disasterinboxcleaner/AdSmsPolicyTest.kt
git commit -q -m "feat: add AdSmsPolicy for legally marked ad SMS"
```

---

### Task 2: AdCleanupHistory (로컬 TSV 이력)

**Files:**
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/AdCleanupHistory.kt`
- Test: `app/src/test/java/com/interbb/disasterinboxcleaner/AdCleanupHistoryTest.kt`

**Interfaces:**
- Consumes: `AdSmsPolicy.RULE_LEGAL_MARK`(테스트 기본값).
- Produces:
  - `data class AdCleanupEntry(val at: Long, val address: String, val snippet: String, val rule: String)`
  - `class AdCleanupHistory(file: File, now: () -> Long = System::currentTimeMillis)` with `append(entries: List<AdCleanupEntry>)`, `list(): List<AdCleanupEntry>`(최신순), `clear()`
  - `AdCleanupHistory.MAX_ENTRIES = 300`, `RETENTION_MS = 30일`, `FILE_NAME = "ad_cleanup_history.tsv"`, `AdCleanupHistory.default(context: Context)`

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/java/com/interbb/disasterinboxcleaner/AdCleanupHistoryTest.kt`:

```kotlin
package com.interbb.disasterinboxcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdCleanupHistoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_700_000_000_000L
    private lateinit var file: File

    private fun history(): AdCleanupHistory {
        file = File(temp.root, "history.tsv")
        return AdCleanupHistory(file) { now }
    }

    private fun entry(
        at: Long,
        address: String = "0212345678",
        snippet: String = "(광고)테스트",
        rule: String = AdSmsPolicy.RULE_LEGAL_MARK,
    ) = AdCleanupEntry(at = at, address = address, snippet = snippet, rule = rule)

    @Test
    fun appendThenListReturnsNewestFirst() {
        val history = history()
        history.append(listOf(entry(now - 2_000), entry(now - 1_000)))
        history.append(listOf(entry(now)))

        val listed = history.list()
        assertEquals(listOf(now, now - 1_000, now - 2_000), listed.map { it.at })
        assertEquals("0212345678", listed[0].address)
        assertEquals("(광고)테스트", listed[0].snippet)
        assertEquals(AdSmsPolicy.RULE_LEGAL_MARK, listed[0].rule)
    }

    @Test
    fun keepsAtMostThreeHundredNewestEntries() {
        val history = history()
        history.append((0 until 301).map { entry(now - it) })

        val listed = history.list()
        assertEquals(300, listed.size)
        assertEquals(now, listed.first().at)
        assertEquals(now - 299, listed.last().at)
    }

    @Test
    fun dropsEntriesOlderThanRetention() {
        val history = history()
        val tooOld = now - AdCleanupHistory.RETENTION_MS - 1
        history.append(listOf(entry(tooOld), entry(now)))

        assertEquals(listOf(now), history.list().map { it.at })
    }

    @Test
    fun corruptFileIsQuarantinedAndListIsEmpty() {
        val history = history()
        file.writeText("not a history file")

        assertTrue(history.list().isEmpty())
        assertFalse(file.exists())
        assertTrue(File(temp.root, "history.tsv.bak").exists())
    }

    @Test
    fun clearRemovesFile() {
        val history = history()
        history.append(listOf(entry(now)))
        history.clear()

        assertFalse(file.exists())
        assertTrue(history.list().isEmpty())
    }

    @Test
    fun tabsAndNewlinesAreFlattenedBeforeStorage() {
        val history = history()
        history.append(listOf(entry(now, address = "a\tb", snippet = "line1\nline2")))

        val stored = history.list().single()
        assertEquals("a b", stored.address)
        assertEquals("line1 line2", stored.snippet)
    }
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
./gradlew --offline testDebugUnitTest --tests 'com.interbb.disasterinboxcleaner.AdCleanupHistoryTest' 2>&1 | grep -E "^e: |BUILD" | head
```
Expected: `Unresolved reference 'AdCleanupHistory'`, `BUILD FAILED`.

- [ ] **Step 3: 구현**

`app/src/main/java/com/interbb/disasterinboxcleaner/AdCleanupHistory.kt`:

```kotlin
package com.interbb.disasterinboxcleaner

import android.content.Context
import java.io.File
import java.io.IOException

data class AdCleanupEntry(
    val at: Long,
    val address: String,
    val snippet: String,
    val rule: String,
)

/**
 * Local-only log of deleted advertising SMS: timestamp, sender, rule and the first 30 characters.
 * Never the full body. Stored as tab-separated text so JVM unit tests need no Android runtime.
 *
 * File format: first line "v1", then one entry per line: at<TAB>address<TAB>rule<TAB>snippet.
 */
class AdCleanupHistory(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun append(entries: List<AdCleanupEntry>) {
        if (entries.isEmpty()) return
        val cutoff = now() - RETENTION_MS
        val merged = (entries + read())
            .filter { it.at >= cutoff }
            .sortedByDescending { it.at }
            .take(MAX_ENTRIES)
        write(merged)
    }

    @Synchronized
    fun list(): List<AdCleanupEntry> = read().sortedByDescending { it.at }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun read(): List<AdCleanupEntry> {
        if (!file.exists()) return emptyList()
        val lines = try {
            file.readLines()
        } catch (_: IOException) {
            quarantine()
            return emptyList()
        }
        if (lines.isEmpty() || lines[0] != HEADER) {
            quarantine()
            return emptyList()
        }
        val entries = ArrayList<AdCleanupEntry>(lines.size)
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split('\t', limit = 4)
            val at = parts.getOrNull(0)?.toLongOrNull()
            if (parts.size < 4 || at == null) {
                quarantine()
                return emptyList()
            }
            entries += AdCleanupEntry(at = at, address = parts[1], rule = parts[2], snippet = parts[3])
        }
        return entries
    }

    private fun write(entries: List<AdCleanupEntry>) {
        file.parentFile?.mkdirs()
        val text = buildString {
            appendLine(HEADER)
            entries.forEach { entry ->
                append(entry.at).append('\t')
                    .append(clean(entry.address)).append('\t')
                    .append(clean(entry.rule)).append('\t')
                    .append(clean(entry.snippet)).append('\n')
            }
        }
        file.writeText(text)
    }

    private fun quarantine() {
        val backup = File(file.parentFile, file.name + ".bak")
        if (backup.exists()) backup.delete()
        file.renameTo(backup)
    }

    companion object {
        const val MAX_ENTRIES = 300
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val FILE_NAME = "ad_cleanup_history.tsv"
        private const val HEADER = "v1"

        fun default(context: Context): AdCleanupHistory =
            AdCleanupHistory(File(context.filesDir, FILE_NAME))

        internal fun clean(value: String): String =
            value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    }
}
```

- [ ] **Step 4: 통과 확인**

Run:
```bash
./gradlew --offline testDebugUnitTest --tests 'com.interbb.disasterinboxcleaner.AdCleanupHistoryTest' 2>&1 | grep -E "^e: |FAILED|BUILD" | head
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/interbb/disasterinboxcleaner/AdCleanupHistory.kt app/src/test/java/com/interbb/disasterinboxcleaner/AdCleanupHistoryTest.kt
git commit -q -m "feat: add local TSV history for deleted ad SMS"
```

---

### Task 3: AdMonitorPreferences (광고 기능 상태 저장)

Android `SharedPreferences`라 JVM 단위 테스트는 없다. 컴파일(`assembleDebug`)로 확인하고 동작은 Task 10 E2E에서 본다.

**Files:**
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/AdMonitorPreferences.kt`

**Interfaces:**
- Consumes: `MonitorError`(기존 enum, `MonitorPreferences.kt`).
- Produces:
  - `data class StoredAdMonitorState(enabled: Boolean, enabledAt: Long, deletedCount: Long, lastDeletedAt: Long, lastError: MonitorError?)`
  - `class AdMonitorPreferences(context)`: `snapshot()`, `isEnabled()`, `setEnabled(Boolean)`, `recordCleanup(Int)`, `recordReady()`, `recordError(MonitorError)`, `registerListener(...)`, `unregisterListener(...)`

- [ ] **Step 1: 구현**

`app/src/main/java/com/interbb/disasterinboxcleaner/AdMonitorPreferences.kt`:

```kotlin
package com.interbb.disasterinboxcleaner

import android.content.Context
import android.content.SharedPreferences

data class StoredAdMonitorState(
    val enabled: Boolean,
    val enabledAt: Long,
    val deletedCount: Long,
    val lastDeletedAt: Long,
    val lastError: MonitorError?,
)

/** State of the ad-SMS cleanup feature. Separate prefs file from the disaster-alert monitor. */
class AdMonitorPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): StoredAdMonitorState = StoredAdMonitorState(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        enabledAt = preferences.getLong(KEY_ENABLED_AT, 0L),
        deletedCount = preferences.getLong(KEY_DELETED_COUNT, 0L),
        lastDeletedAt = preferences.getLong(KEY_LAST_DELETED_AT, 0L),
        lastError = preferences.getString(KEY_LAST_ERROR, null)?.let { stored ->
            MonitorError.entries.firstOrNull { it.name == stored }
        },
    )

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        val editor = preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .remove(KEY_LAST_ERROR)
        if (enabled) {
            editor.putLong(KEY_ENABLED_AT, System.currentTimeMillis())
        }
        editor.apply()
    }

    @Synchronized
    fun recordCleanup(deleted: Int) {
        if (deleted <= 0) return
        preferences.edit()
            .putLong(KEY_DELETED_COUNT, preferences.getLong(KEY_DELETED_COUNT, 0L) + deleted)
            .putLong(KEY_LAST_DELETED_AT, System.currentTimeMillis())
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordReady() {
        preferences.edit().remove(KEY_LAST_ERROR).apply()
    }

    fun recordError(error: MonitorError) {
        preferences.edit().putString(KEY_LAST_ERROR, error.name).apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val PREFS_NAME = "ad_monitor_state"
        const val KEY_ENABLED = "enabled"
        const val KEY_ENABLED_AT = "enabled_at"
        const val KEY_DELETED_COUNT = "deleted_count"
        const val KEY_LAST_DELETED_AT = "last_deleted_at"
        const val KEY_LAST_ERROR = "last_error"
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run:
```bash
./gradlew --offline -q assembleDebug 2>&1 | grep -E "^e: |BUILD FAILED" | head; echo "exit=${PIPESTATUS[0]}"
```
Expected: 출력 없음, `exit=0`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/interbb/disasterinboxcleaner/AdMonitorPreferences.kt
git commit -q -m "feat: add AdMonitorPreferences"
```

---

### Task 4: AdInboxCleaner (조회·삭제·이력 기록)

**Files:**
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/AdInboxCleaner.kt`
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/DisasterMessageRepository.kt:8-12` (`DisasterMessageItem`에 `address` 추가)

**Interfaces:**
- Consumes: `AdSmsPolicy`(Task 1), `AdCleanupHistory`·`AdCleanupEntry`(Task 2), `PermissionState.canReadSms/canDeleteSms`, `MonitorError`, `HistoryLoadResult`, `ManualDeleteResult`, `DisasterMessageItem`(모두 기존).
- Produces:
  - `data class AdCandidate(id: Long, address: String, receivedAt: Long, body: String)`
  - `sealed interface AdCleanupResult { Success(deleted: Int, addresses: List<String>); Failure(error: MonitorError) }`
  - `class AdInboxCleaner(context, history)`: `cleanSince(enabledAt: Long): AdCleanupResult`, `listCandidates(): HistoryLoadResult`, `deleteByIds(ids: Set<Long>): ManualDeleteResult`, `deleteAll(): ManualDeleteResult`
  - `DisasterMessageItem.address: String`(기본값 `""`)

- [ ] **Step 1: DisasterMessageItem에 address 추가**

`DisasterMessageRepository.kt` 8-12행을 다음으로 교체:

```kotlin
data class DisasterMessageItem(
    val id: Long,
    val receivedAt: Long,
    val body: String,
    val address: String = "",
)
```

- [ ] **Step 2: AdInboxCleaner 구현**

`app/src/main/java/com/interbb/disasterinboxcleaner/AdInboxCleaner.kt`:

```kotlin
package com.interbb.disasterinboxcleaner

import android.content.Context
import android.provider.BaseColumns
import android.provider.Telephony

data class AdCandidate(
    val id: Long,
    val address: String,
    val receivedAt: Long,
    val body: String,
)

sealed interface AdCleanupResult {
    data class Success(val deleted: Int, val addresses: List<String>) : AdCleanupResult
    data class Failure(val error: MonitorError) : AdCleanupResult
}

/**
 * Finds and deletes legally marked advertising SMS in the inbox.
 *
 * All queries and deletes go through Telephony.Sms.CONTENT_URI (content://sms). The provider
 * rejects deletes on content://sms/inbox ("Unknown URL") and ignores where clauses on
 * content://sms/N, so every delete is "content://sms + selection by id".
 */
class AdInboxCleaner(
    private val context: Context,
    private val history: AdCleanupHistory,
) {
    /** Automatic path: delete marked inbox rows received at or after [enabledAt]. */
    fun cleanSince(enabledAt: Long): AdCleanupResult {
        if (!PermissionState.canReadSms(context)) {
            return AdCleanupResult.Failure(MonitorError.SMS_READ_PERMISSION_REQUIRED)
        }
        if (!PermissionState.canDeleteSms(context)) {
            return AdCleanupResult.Failure(MonitorError.SMS_DELETE_PERMISSION_REQUIRED)
        }
        if (enabledAt <= 0L) {
            return AdCleanupResult.Success(0, emptyList())
        }

        return try {
            val candidates = queryCandidates(
                AdSmsPolicy.autoDeleteSelection,
                AdSmsPolicy.autoDeleteSelectionArgs(enabledAt),
            )
            val deleted = deleteCandidates(candidates, AdSmsPolicy.RULE_LEGAL_MARK)
            AdCleanupResult.Success(deleted.size, deleted.map { it.address })
        } catch (_: SecurityException) {
            AdCleanupResult.Failure(MonitorError.SMS_DELETE_PERMISSION_REQUIRED)
        } catch (_: RuntimeException) {
            AdCleanupResult.Failure(MonitorError.SMS_PROVIDER_REJECTED)
        }
    }

    /** Manual screen: every marked inbox row, newest first. Bodies stay in memory only. */
    fun listCandidates(): HistoryLoadResult {
        if (!PermissionState.canReadSms(context)) {
            return HistoryLoadResult.ReadPermissionRequired
        }

        return try {
            val items = queryCandidates(AdSmsPolicy.inboxAdSelection, null).map { candidate ->
                DisasterMessageItem(
                    id = candidate.id,
                    receivedAt = candidate.receivedAt,
                    body = candidate.body,
                    address = candidate.address,
                )
            }
            HistoryLoadResult.Success(items)
        } catch (_: SecurityException) {
            HistoryLoadResult.ReadPermissionRequired
        } catch (_: RuntimeException) {
            HistoryLoadResult.ProviderRejected
        }
    }

    fun deleteByIds(ids: Set<Long>): ManualDeleteResult = manualDelete { candidates ->
        candidates.filter { it.id in ids }
    }

    fun deleteAll(): ManualDeleteResult = manualDelete { candidates -> candidates }

    private fun manualDelete(select: (List<AdCandidate>) -> List<AdCandidate>): ManualDeleteResult {
        if (!PermissionState.canDeleteSms(context)) {
            return ManualDeleteResult.DeletePermissionRequired
        }

        return try {
            val candidates = select(queryCandidates(AdSmsPolicy.inboxAdSelection, null))
            val deleted = deleteCandidates(candidates, AdSmsPolicy.RULE_MANUAL)
            ManualDeleteResult.Success(deleted.size)
        } catch (_: SecurityException) {
            ManualDeleteResult.DeletePermissionRequired
        } catch (_: RuntimeException) {
            ManualDeleteResult.ProviderRejected
        }
    }

    private fun queryCandidates(selection: String, selectionArgs: Array<String>?): List<AdCandidate> =
        buildList {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                PROJECTION,
                selection,
                selectionArgs,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                while (cursor.moveToNext()) {
                    add(
                        AdCandidate(
                            id = cursor.getLong(idIndex),
                            address = cursor.getString(addressIndex).orEmpty(),
                            receivedAt = cursor.getLong(dateIndex),
                            body = cursor.getString(bodyIndex).orEmpty(),
                        ),
                    )
                }
            }
        }

    /** Deletes one row at a time. Only rows the provider reports as deleted are logged. */
    private fun deleteCandidates(candidates: List<AdCandidate>, rule: String): List<AdCandidate> {
        val deleted = candidates.filter { candidate ->
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                AdSmsPolicy.byIdSelection,
                AdSmsPolicy.byIdSelectionArgs(candidate.id),
            ) == 1
        }
        if (deleted.isNotEmpty()) {
            val now = System.currentTimeMillis()
            history.append(
                deleted.map { candidate ->
                    AdCleanupEntry(
                        at = now,
                        address = candidate.address,
                        snippet = AdSmsPolicy.snippet(candidate.body),
                        rule = rule,
                    )
                },
            )
        }
        return deleted
    }

    private companion object {
        val PROJECTION = arrayOf(
            BaseColumns._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
        )
    }
}
```

- [ ] **Step 3: 컴파일과 기존 테스트 회귀 확인**

Run:
```bash
./gradlew --offline testDebugUnitTest assembleDebug 2>&1 | grep -E "^e: |FAILED|BUILD" | head
```
Expected: `BUILD SUCCESSFUL`(기존 3건 + 새 9건 통과).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/interbb/disasterinboxcleaner/AdInboxCleaner.kt app/src/main/java/com/interbb/disasterinboxcleaner/DisasterMessageRepository.kt
git commit -q -m "feat: add AdInboxCleaner (query, delete by selection, log)"
```

---

### Task 5: 리스너 서비스에 광고 트리거·정리·알림 닫기 추가

**Files:**
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/DisasterNotificationListenerService.kt` (전체 교체)

**Interfaces:**
- Consumes: `AdMonitorPreferences`(Task 3), `AdInboxCleaner`·`AdCleanupResult`(Task 4), `AdCleanupHistory.default`(Task 2), `AdSmsPolicy.isAd`(Task 1), 기존 `MonitorPreferences`·`EmergencyInboxCleaner`·`MonitorRuntime`·`PermissionState`.
- Produces: 없음(서비스). 상수 `SAMSUNG_MESSAGES_PACKAGE = "com.samsung.android.messaging"`.

재난문자 분기는 그대로이고, 광고 분기가 나란히 붙는다. 매니페스트의 `default_filter_types="alerting"`은 그대로 둔다. 삼성 메시지의 새 문자 알림은 alerting이라 전달되고, 음소거 대화의 silent 알림은 전달되지 않지만 그 경우 `content://sms` 관찰자가 삭제를 담당하므로 알림 닫기가 필요 없다.

- [ ] **Step 1: 파일 전체 교체**

```kotlin
package com.interbb.disasterinboxcleaner

import android.app.Notification
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class DisasterNotificationListenerService : NotificationListenerService() {
    private lateinit var monitorPreferences: MonitorPreferences
    private lateinit var adPreferences: AdMonitorPreferences
    private lateinit var inboxCleaner: EmergencyInboxCleaner
    private lateinit var adCleaner: AdInboxCleaner
    private lateinit var worker: ScheduledExecutorService
    private var inboxObserver: ContentObserver? = null
    private var adObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        monitorPreferences = MonitorPreferences(this)
        adPreferences = AdMonitorPreferences(this)
        inboxCleaner = EmergencyInboxCleaner(this)
        adCleaner = AdInboxCleaner(this, AdCleanupHistory.default(this))
        worker = Executors.newSingleThreadScheduledExecutor()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        MonitorRuntime.setListenerConnected(true)
        registerInboxObserverIfPossible()
        registerAdObserverIfPossible()
        scheduleCleanup(0L)
        scheduleAdCleanup(0L)
    }

    override fun onListenerDisconnected() {
        MonitorRuntime.setListenerConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        when (notification.packageName) {
            CELL_BROADCAST_PACKAGE -> {
                monitorPreferences.recordEvent()
                if (!monitorPreferences.isEnabled()) return

                registerInboxObserverIfPossible()
                RETRY_DELAYS_MS.forEach(::scheduleCleanup)
            }
            SAMSUNG_MESSAGES_PACKAGE -> {
                if (!adPreferences.isEnabled()) return

                registerAdObserverIfPossible()
                RETRY_DELAYS_MS.forEach(::scheduleAdCleanup)
            }
        }
    }

    private fun registerInboxObserverIfPossible() {
        if (inboxObserver != null || !PermissionState.canReadSms(this)) return

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (monitorPreferences.isEnabled()) {
                    scheduleCleanup(CONTENT_OBSERVER_DELAY_MS)
                }
            }
        }

        try {
            contentResolver.registerContentObserver(
                Telephony.Sms.Inbox.CONTENT_URI,
                true,
                observer,
            )
            inboxObserver = observer
        } catch (_: SecurityException) {
            monitorPreferences.recordError(MonitorError.SMS_READ_PERMISSION_REQUIRED)
        }
    }

    /** Watches content://sms (all boxes) so ad cleanup runs whichever URI the default app writes to. */
    private fun registerAdObserverIfPossible() {
        if (adObserver != null || !PermissionState.canReadSms(this)) return

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (adPreferences.isEnabled()) {
                    scheduleAdCleanup(CONTENT_OBSERVER_DELAY_MS)
                }
            }
        }

        try {
            contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                observer,
            )
            adObserver = observer
        } catch (_: SecurityException) {
            adPreferences.recordError(MonitorError.SMS_READ_PERMISSION_REQUIRED)
        }
    }

    private fun scheduleCleanup(delayMs: Long) {
        if (worker.isShutdown) return
        worker.schedule(::performCleanup, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleAdCleanup(delayMs: Long) {
        if (worker.isShutdown) return
        worker.schedule(::performAdCleanup, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun performCleanup() {
        val state = monitorPreferences.snapshot()
        if (!state.enabled) return

        when (val result = inboxCleaner.deleteCopiesSince(state.monitoringStartedAt)) {
            is CleanupResult.Success -> {
                if (result.deleted > 0) {
                    monitorPreferences.recordCleanup(result.deleted)
                } else {
                    monitorPreferences.recordReady()
                }
            }
            is CleanupResult.Failure -> monitorPreferences.recordError(result.error)
        }
    }

    private fun performAdCleanup() {
        val state = adPreferences.snapshot()
        if (!state.enabled) return

        when (val result = adCleaner.cleanSince(state.enabledAt)) {
            is AdCleanupResult.Success -> {
                if (result.deleted > 0) {
                    adPreferences.recordCleanup(result.deleted)
                    cancelAdNotifications()
                } else {
                    adPreferences.recordReady()
                }
            }
            is AdCleanupResult.Failure -> adPreferences.recordError(result.error)
        }
    }

    /** Best effort: close Samsung Messages notifications whose visible text starts with the ad mark. */
    private fun cancelAdNotifications() {
        val active = try {
            activeNotifications
        } catch (_: SecurityException) {
            return
        } ?: return

        active
            .filter { it.packageName == SAMSUNG_MESSAGES_PACKAGE && notificationLooksLikeAd(it) }
            .forEach { notification -> runCatching { cancelNotification(notification.key) } }
    }

    private fun notificationLooksLikeAd(notification: StatusBarNotification): Boolean {
        val extras = notification.notification.extras ?: return false
        val texts = mutableListOf<CharSequence?>()
        texts += extras.getCharSequence(Notification.EXTRA_TEXT)
        texts += extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { texts += it }
        return texts.any { AdSmsPolicy.isAd(it?.toString()) }
    }

    override fun onDestroy() {
        inboxObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        inboxObserver = null
        adObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        adObserver = null
        worker.shutdownNow()
        MonitorRuntime.setListenerConnected(false)
        super.onDestroy()
    }

    private companion object {
        const val CELL_BROADCAST_PACKAGE = "com.google.android.cellbroadcastreceiver"
        const val SAMSUNG_MESSAGES_PACKAGE = "com.samsung.android.messaging"
        const val CONTENT_OBSERVER_DELAY_MS = 300L
        val RETRY_DELAYS_MS = longArrayOf(400L, 1_500L, 4_000L)
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run:
```bash
./gradlew --offline -q assembleDebug 2>&1 | grep -E "^e: |BUILD FAILED" | head; echo "exit=${PIPESTATUS[0]}"
```
Expected: 출력 없음, `exit=0`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/interbb/disasterinboxcleaner/DisasterNotificationListenerService.kt
git commit -q -m "feat: trigger ad cleanup from sms observer and Samsung Messages notifications"
```

---

### Task 6: ViewModel 상태 확장 (광고 상태, HistorySource, 이력 목록)

**Files:**
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/MainViewModel.kt` (전체 교체)
- Test: 기존 `app/src/test/java/com/interbb/disasterinboxcleaner/HistoryUiStateTest.kt`에 1건 추가

**Interfaces:**
- Consumes: Task 2·3·4 산출물, 기존 `MonitorPreferences`, `DisasterMessageRepository`, `PermissionState`, `MonitorRuntime`.
- Produces:
  - `data class AdUiState(enabled, deletedCount, lastDeletedAt, lastError)`; `MonitorUiState.ad: AdUiState`, `MonitorUiState.isAdMonitoring: Boolean`
  - `enum class HistorySource { DISASTER, AD }`; `HistoryUiState.source: HistorySource`
  - `MainViewModel.setAdEnabled(Boolean)`, `loadHistory(source: HistorySource = 현재 source)`, `adLogState: StateFlow<List<AdCleanupEntry>>`, `loadAdLog()`, `clearAdLog()`

- [ ] **Step 1: 실패하는 테스트 추가**

`HistoryUiStateTest.kt`의 클래스 안, 기존 테스트 아래에 추가:

```kotlin
    @Test
    fun adSourceNeverReportsRestrictedListing() {
        val state = HistoryUiState(source = HistorySource.AD, messages = emptyList())
        assertFalse(state.individualListingRestricted)
        assertFalse(state.canRequestDeleteAll)
        assertEquals(HistorySource.DISASTER, HistoryUiState().source)
    }
```

파일 상단 import에 `import org.junit.Assert.assertEquals` 추가.

- [ ] **Step 2: 실패 확인**

Run:
```bash
./gradlew --offline testDebugUnitTest --tests 'com.interbb.disasterinboxcleaner.HistoryUiStateTest' 2>&1 | grep -E "^e: |BUILD" | head
```
Expected: `Unresolved reference 'HistorySource'`, `BUILD FAILED`.

- [ ] **Step 3: MainViewModel 전체 교체**

```kotlin
package com.interbb.disasterinboxcleaner

import android.app.Application
import android.content.ComponentName
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class AdUiState(
    val enabled: Boolean = false,
    val deletedCount: Long = 0L,
    val lastDeletedAt: Long = 0L,
    val lastError: MonitorError? = null,
)

@Immutable
data class MonitorUiState(
    val onboardingComplete: Boolean = false,
    val enabled: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val smsReadGranted: Boolean = false,
    val smsDeleteGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val deletedCount: Long = 0L,
    val lastDeletedAt: Long = 0L,
    val lastEventAt: Long = 0L,
    val lastError: MonitorError? = null,
    val ad: AdUiState = AdUiState(),
) {
    val allPermissionsReady: Boolean
        get() = notificationAccessGranted && smsReadGranted && smsDeleteGranted

    val isMonitoring: Boolean
        get() = enabled && allPermissionsReady && listenerConnected

    val isAdMonitoring: Boolean
        get() = ad.enabled && allPermissionsReady && listenerConnected
}

enum class HistorySource {
    DISASTER,
    AD,
}

enum class HistoryError {
    READ_PERMISSION_REQUIRED,
    DELETE_PERMISSION_REQUIRED,
    PROVIDER_REJECTED,
}

@Immutable
data class HistoryUiState(
    val source: HistorySource = HistorySource.DISASTER,
    val loading: Boolean = false,
    val messages: List<DisasterMessageItem> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val individualListingRestricted: Boolean = false,
    val notice: String? = null,
    val error: HistoryError? = null,
) {
    val allSelected: Boolean
        get() = messages.isNotEmpty() && selectedIds.size == messages.size

    val canRequestDeleteAll: Boolean
        get() = messages.isNotEmpty() || individualListingRestricted
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val monitorPreferences = MonitorPreferences(application)
    private val adPreferences = AdMonitorPreferences(application)
    private val messageRepository = DisasterMessageRepository(application)
    private val adHistory = AdCleanupHistory.default(application)
    private val adCleaner = AdInboxCleaner(application, adHistory)
    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState = _uiState.asStateFlow()
    private val _historyState = MutableStateFlow(HistoryUiState())
    val historyState = _historyState.asStateFlow()
    private val _adLogState = MutableStateFlow<List<AdCleanupEntry>>(emptyList())
    val adLogState = _adLogState.asStateFlow()

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init {
        monitorPreferences.registerListener(preferenceListener)
        adPreferences.registerListener(preferenceListener)
        viewModelScope.launch {
            MonitorRuntime.listenerConnected.collect { refresh() }
        }
        refresh()
    }

    fun setEnabled(enabled: Boolean) {
        monitorPreferences.setEnabled(enabled)
        refreshAndRequestRebind()
    }

    fun setAdEnabled(enabled: Boolean) {
        adPreferences.setEnabled(enabled)
        refreshAndRequestRebind()
    }

    fun completeOnboarding(): Boolean {
        refresh()
        if (!_uiState.value.allPermissionsReady) return false
        monitorPreferences.completeOnboarding()
        refreshAndRequestRebind()
        return true
    }

    fun refreshAndRequestRebind() {
        refresh()
        val context = getApplication<Application>()
        if (PermissionState.hasNotificationAccess(context)) {
            NotificationListenerService.requestRebind(
                ComponentName(context, DisasterNotificationListenerService::class.java),
            )
        }
    }

    fun refresh() {
        val context = getApplication<Application>()
        val stored = monitorPreferences.snapshot()
        val storedAd = adPreferences.snapshot()
        _uiState.value = MonitorUiState(
            onboardingComplete = stored.onboardingComplete,
            enabled = stored.enabled,
            notificationAccessGranted = PermissionState.hasNotificationAccess(context),
            smsReadGranted = PermissionState.canReadSms(context),
            smsDeleteGranted = PermissionState.canDeleteSms(context),
            listenerConnected = MonitorRuntime.listenerConnected.value,
            deletedCount = stored.deletedCount,
            lastDeletedAt = stored.lastDeletedAt,
            lastEventAt = stored.lastEventAt,
            lastError = stored.lastError,
            ad = AdUiState(
                enabled = storedAd.enabled,
                deletedCount = storedAd.deletedCount,
                lastDeletedAt = storedAd.lastDeletedAt,
                lastError = storedAd.lastError,
            ),
        )
    }

    fun loadHistory(source: HistorySource = _historyState.value.source) {
        loadHistoryWithNotice(source, null)
    }

    private fun loadHistoryWithNotice(source: HistorySource, notice: String?) {
        viewModelScope.launch {
            _historyState.value = HistoryUiState(source = source, loading = true)
            val result = withContext(Dispatchers.IO) {
                when (source) {
                    HistorySource.DISASTER -> messageRepository.loadMessages()
                    HistorySource.AD -> adCleaner.listCandidates()
                }
            }
            _historyState.value = when (result) {
                is HistoryLoadResult.Success -> HistoryUiState(
                    source = source,
                    messages = result.messages,
                    individualListingRestricted = source == HistorySource.DISASTER &&
                        !PermissionState.isDefaultSmsApp(getApplication<Application>()),
                    notice = notice,
                )
                HistoryLoadResult.ReadPermissionRequired ->
                    HistoryUiState(source = source, error = HistoryError.READ_PERMISSION_REQUIRED)
                HistoryLoadResult.ProviderRejected ->
                    HistoryUiState(source = source, error = HistoryError.PROVIDER_REJECTED)
            }
        }
    }

    fun toggleHistorySelection(id: Long) {
        _historyState.value = _historyState.value.let { state ->
            state.copy(
                selectedIds = if (id in state.selectedIds) {
                    state.selectedIds - id
                } else {
                    state.selectedIds + id
                },
            )
        }
    }

    fun toggleSelectAllHistory() {
        _historyState.value = _historyState.value.let { state ->
            state.copy(
                selectedIds = if (state.allSelected) {
                    emptySet()
                } else {
                    state.messages.mapTo(mutableSetOf()) { it.id }
                },
            )
        }
    }

    fun deleteSelectedHistory() {
        val state = _historyState.value
        val ids = state.selectedIds
        if (ids.isEmpty()) return
        deleteHistory(state.source) {
            when (state.source) {
                HistorySource.DISASTER -> messageRepository.deleteMessages(ids)
                HistorySource.AD -> adCleaner.deleteByIds(ids)
            }
        }
    }

    fun deleteAllHistory() {
        val state = _historyState.value
        if (!state.canRequestDeleteAll) return
        deleteHistory(state.source) {
            when (state.source) {
                HistorySource.DISASTER -> messageRepository.deleteAllMessages()
                HistorySource.AD -> adCleaner.deleteAll()
            }
        }
    }

    private fun deleteHistory(source: HistorySource, operation: suspend () -> ManualDeleteResult) {
        viewModelScope.launch {
            _historyState.value = _historyState.value.copy(loading = true, error = null)
            when (val result = withContext(Dispatchers.IO) { operation() }) {
                is ManualDeleteResult.Success -> {
                    when (source) {
                        HistorySource.DISASTER -> monitorPreferences.recordCleanup(result.deleted)
                        HistorySource.AD -> adPreferences.recordCleanup(result.deleted)
                    }
                    loadHistoryWithNotice(source, "삼성 메시지함에서 ${result.deleted}개를 삭제했습니다.")
                }
                ManualDeleteResult.DeletePermissionRequired -> {
                    _historyState.value = _historyState.value.copy(
                        loading = false,
                        error = HistoryError.DELETE_PERMISSION_REQUIRED,
                    )
                }
                ManualDeleteResult.ProviderRejected -> {
                    _historyState.value = _historyState.value.copy(
                        loading = false,
                        error = HistoryError.PROVIDER_REJECTED,
                    )
                }
            }
        }
    }

    fun loadAdLog() {
        viewModelScope.launch {
            _adLogState.value = withContext(Dispatchers.IO) { adHistory.list() }
        }
    }

    fun clearAdLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { adHistory.clear() }
            _adLogState.value = emptyList()
        }
    }

    fun clearHistoryFromMemory() {
        _historyState.value = HistoryUiState()
    }

    override fun onCleared() {
        clearHistoryFromMemory()
        monitorPreferences.unregisterListener(preferenceListener)
        adPreferences.unregisterListener(preferenceListener)
    }
}
```

- [ ] **Step 4: 통과 확인 (테스트 + 컴파일)**

Run:
```bash
./gradlew --offline testDebugUnitTest assembleDebug 2>&1 | grep -E "^e: |FAILED|BUILD" | head
```
Expected: `BUILD SUCCESSFUL`. `MainActivity`는 `loadHistory()`를 인자 없이 호출하므로 기본값 덕분에 그대로 컴파일된다.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/interbb/disasterinboxcleaner/MainViewModel.kt app/src/test/java/com/interbb/disasterinboxcleaner/HistoryUiStateTest.kt
git commit -q -m "feat: ad state, history source and ad log in MainViewModel"
```

---

### Task 7: HistoryScreen을 source별 문구로 재사용

**Files:**
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/ui/HistoryScreen.kt` (전체 교체)

**Interfaces:**
- Consumes: `HistoryUiState.source`, `HistorySource`(Task 6), `DisasterMessageItem.address`(Task 4).
- Produces: `HistoryScreen(...)` 시그니처는 그대로(문구는 `state.source`로 결정).

- [ ] **Step 1: 파일 전체 교체**

```kotlin
package com.interbb.disasterinboxcleaner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.interbb.disasterinboxcleaner.DisasterMessageItem
import com.interbb.disasterinboxcleaner.HistoryError
import com.interbb.disasterinboxcleaner.HistorySource
import com.interbb.disasterinboxcleaner.HistoryUiState

private enum class DeleteConfirmation {
    SELECTED,
    ALL,
}

/** Screen copy that differs between the disaster-copy list and the ad-SMS list. */
private class HistoryCopy(
    val title: String,
    val countLabel: (Int) -> String,
    val deleteAllLabel: String,
    val emptyText: String,
    val dialogTarget: (count: Int, allRestricted: Boolean) -> String,
    val dialogSuffix: String,
)

private fun copyFor(source: HistorySource): HistoryCopy = when (source) {
    HistorySource.DISASTER -> HistoryCopy(
        title = "기존 재난문자 정리",
        countLabel = { "찾은 복사본 ${it}개" },
        deleteAllLabel = "메시지함의 재난문자 모두 삭제",
        emptyText = "삼성 메시지함에 보이는 재난문자 복사본이 없습니다.",
        dialogTarget = { count, allRestricted ->
            if (allRestricted) "삼성 메시지함의 #CMAS# 재난문자 복사본을 모두" else "${count}개의 재난문자 복사본을"
        },
        dialogSuffix = "시스템 재난문자 기록과 알림에는 영향을 주지 않습니다.",
    )
    HistorySource.AD -> HistoryCopy(
        title = "기존 광고 문자 정리",
        countLabel = { "찾은 광고 문자 ${it}개" },
        deleteAllLabel = "메시지함의 광고 문자 모두 삭제",
        emptyText = "삼성 메시지함에 (광고)/[광고]로 시작하는 문자가 없습니다.",
        dialogTarget = { count, _ -> "${count}개의 광고 문자를" },
        dialogSuffix = "삭제한 문자는 되돌릴 수 없습니다.",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    canDelete: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onToggleSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteAll: () -> Unit,
    onOpenMessagingApp: () -> Unit,
) {
    val copy = copyFor(state.source)
    var confirmation by remember { mutableStateOf<DeleteConfirmation?>(null) }

    confirmation?.let { action ->
        val count = if (action == DeleteConfirmation.ALL) state.messages.size else state.selectedIds.size
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("메시지함에서 삭제할까요?") },
            text = {
                val target = copy.dialogTarget(
                    count,
                    action == DeleteConfirmation.ALL && state.individualListingRestricted,
                )
                Text("$target 삭제합니다. ${copy.dialogSuffix}")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmation = null
                        if (action == DeleteConfirmation.ALL) onDeleteAll() else onDeleteSelected()
                    },
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text("취소") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(copy.title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "이 화면에서만 본문을 기기 메모리에 불러옵니다. 앱을 나가면 목록을 비웁니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.loading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
            } else {
                state.error?.let { error ->
                    item {
                        Text(historyErrorLabel(error), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                            Text("다시 불러오기")
                        }
                    }
                }
                if (state.error == null) {
                    state.notice?.let { notice ->
                        item {
                            Text(notice, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (state.individualListingRestricted) {
                                    "개별 목록 제한됨"
                                } else {
                                    copy.countLabel(state.messages.size)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            TextButton(onClick = onRefresh) { Text("새로고침") }
                        }
                    }
                    if (state.individualListingRestricted) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        "개별 목록은 삼성 메시지에서 확인",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "삼성 메시지가 기본 문자 앱인 동안 Android가 다른 앱에는 제한된 " +
                                            "목록을 제공하며, 이 기기에서는 재난문자 항목이 표시되지 않습니다. " +
                                            "개별 선택 삭제는 삼성 메시지에서 진행하세요.",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    OutlinedButton(
                                        onClick = onOpenMessagingApp,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("삼성 메시지 열기")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onToggleSelectAll,
                                enabled = state.messages.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (state.allSelected) "선택 해제" else "모두 선택")
                            }
                            Button(
                                onClick = { confirmation = DeleteConfirmation.SELECTED },
                                enabled = canDelete && state.selectedIds.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("선택 삭제")
                            }
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = { confirmation = DeleteConfirmation.ALL },
                            enabled = canDelete && state.canRequestDeleteAll,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(copy.deleteAllLabel)
                        }
                    }
                    if (!canDelete && state.messages.isNotEmpty()) {
                        item {
                            Text(
                                "삭제하려면 설정에서 PC용 ADB 메시지함 삭제 권한을 승인하세요.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (state.messages.isEmpty() && !state.individualListingRestricted) {
                        item {
                            Text(
                                copy.emptyText,
                                modifier = Modifier.padding(vertical = 32.dp),
                            )
                        }
                    }
                    items(state.messages, key = { it.id }) { message ->
                        MessageSelectionCard(
                            message = message,
                            selected = message.id in state.selectedIds,
                            onToggle = { onToggleSelection(message.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageSelectionCard(
    message: DisasterMessageItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = selected, onCheckedChange = null)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    formatTime(message.receivedAt),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (message.address.isNotBlank()) {
                    Text(
                        message.address,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = message.body.ifBlank { "내용 없음" },
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun historyErrorLabel(error: HistoryError): String = when (error) {
    HistoryError.READ_PERMISSION_REQUIRED -> "목록을 보려면 문자 읽기 권한이 필요합니다."
    HistoryError.DELETE_PERMISSION_REQUIRED -> "메시지함 삭제 권한이 필요합니다."
    HistoryError.PROVIDER_REJECTED -> "삼성 메시지함을 불러오거나 변경하지 못했습니다."
}
```

- [ ] **Step 2: 컴파일 확인**

Run:
```bash
./gradlew --offline -q assembleDebug 2>&1 | grep -E "^e: |BUILD FAILED" | head; echo "exit=${PIPESTATUS[0]}"
```
Expected: 출력 없음, `exit=0`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/interbb/disasterinboxcleaner/ui/HistoryScreen.kt
git commit -q -m "feat: source-aware copy and sender line in HistoryScreen"
```

---

### Task 8: AdLogScreen (광고 삭제 이력 화면)

**Files:**
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/ui/AdLogScreen.kt`

**Interfaces:**
- Consumes: `AdCleanupEntry`, `AdCleanupHistory.MAX_ENTRIES/RETENTION_MS`(Task 2), `AdSmsPolicy.RULE_MANUAL/SNIPPET_LENGTH`(Task 1), 기존 `formatTime`(UiComponents.kt).
- Produces: `AdLogScreen(entries: List<AdCleanupEntry>, onBack: () -> Unit, onRefresh: () -> Unit, onClear: () -> Unit)`

- [ ] **Step 1: 구현**

```kotlin
package com.interbb.disasterinboxcleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.interbb.disasterinboxcleaner.AdCleanupEntry
import com.interbb.disasterinboxcleaner.AdCleanupHistory
import com.interbb.disasterinboxcleaner.AdSmsPolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdLogScreen(
    entries: List<AdCleanupEntry>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("이력을 지울까요?") },
            text = { Text("광고 삭제 이력 ${entries.size}건을 앱에서 지웁니다. 문자함에는 영향이 없습니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) { Text("지우기") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("취소") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("광고 삭제 이력") },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
                actions = { TextButton(onClick = onRefresh) { Text("새로고침") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "최근 ${AdCleanupHistory.MAX_ENTRIES}건, " +
                        "${AdCleanupHistory.RETENTION_MS / 86_400_000L}일까지 보관합니다. " +
                        "본문은 앞 ${AdSmsPolicy.SNIPPET_LENGTH}자만 남깁니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = entries.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("이력 지우기")
                }
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        "아직 삭제한 광고 문자가 없습니다.",
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            itemsIndexed(entries) { _, entry ->
                AdLogCard(entry)
            }
        }
    }
}

@Composable
private fun AdLogCard(entry: AdCleanupEntry) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatTime(entry.at),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    ruleLabel(entry.rule),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                entry.address.ifBlank { "발신번호 없음" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                entry.snippet.ifBlank { "내용 없음" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun ruleLabel(rule: String): String = when (rule) {
    AdSmsPolicy.RULE_MANUAL -> "수동 삭제"
    else -> "자동 삭제"
}
```

- [ ] **Step 2: 컴파일 확인**

Run:
```bash
./gradlew --offline -q assembleDebug 2>&1 | grep -E "^e: |BUILD FAILED" | head; echo "exit=${PIPESTATUS[0]}"
```
Expected: 출력 없음, `exit=0`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/interbb/disasterinboxcleaner/ui/AdLogScreen.kt
git commit -q -m "feat: add AdLogScreen"
```

---

### Task 9: 홈 화면·내비게이션·온보딩 문구

**Files:**
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/ui/HomeScreen.kt` (전체 교체)
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/MainActivity.kt` (전체 교체)
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/ui/OnboardingScreen.kt:55-58, 75-76` (문구 2곳)

**Interfaces:**
- Consumes: `MonitorUiState.ad/isAdMonitoring`, `HistorySource`, `MainViewModel.setAdEnabled/loadHistory(source)/adLogState/loadAdLog/clearAdLog`(Task 6), `AdLogScreen`(Task 8).
- Produces: `HomeScreen(state, onEnabledChange, onAdEnabledChange, onOpenHistory, onOpenAdHistory, onOpenAdLog, onOpenSettings)`; `AppScreen.AD_LOG`.

- [ ] **Step 1: HomeScreen 전체 교체**

```kotlin
package com.interbb.disasterinboxcleaner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.interbb.disasterinboxcleaner.MonitorUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: MonitorUiState,
    onEnabledChange: (Boolean) -> Unit,
    onAdEnabledChange: (Boolean) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdHistory: () -> Unit,
    onOpenAdLog: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("문자함 정리") },
                actions = { TextButton(onClick = onOpenSettings) { Text("설정") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                FeatureSwitchCard(
                    title = "재난문자 복사본 정리",
                    enabled = state.enabled,
                    isMonitoring = state.isMonitoring,
                    enabledText = "새 재난문자 복사본을 자동 정리합니다.",
                    disabledText = "재난문자 복사본 자동 정리를 사용하지 않습니다.",
                    onEnabledChange = onEnabledChange,
                )
            }
            item {
                FeatureSwitchCard(
                    title = "광고 문자 자동 삭제",
                    enabled = state.ad.enabled,
                    isMonitoring = state.isAdMonitoring,
                    enabledText = "본문이 (광고)/[광고]로 시작하는 문자를 수신 즉시 삭제하고 알림을 닫습니다.",
                    disabledText = "광고 문자 자동 삭제를 사용하지 않습니다.",
                    onEnabledChange = onAdEnabledChange,
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("현재 상태", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        StatusRow("감시 서비스", state.listenerConnected)
                        StatusRow("알림 접근", state.notificationAccessGranted)
                        StatusRow("문자 읽기", state.smsReadGranted)
                        StatusRow("메시지함 삭제", state.smsDeleteGranted)
                        Text("정리한 재난문자 복사본: ${state.deletedCount}개")
                        Text("마지막 재난문자 감지: ${formatTime(state.lastEventAt)}")
                        Text("마지막 재난문자 정리: ${formatTime(state.lastDeletedAt)}")
                        state.lastError?.let { error ->
                            Text(
                                "재난문자: " + monitorErrorLabel(error),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text("삭제한 광고 문자: ${state.ad.deletedCount}개")
                        Text("마지막 광고 삭제: ${formatTime(state.ad.lastDeletedAt)}")
                        state.ad.lastError?.let { error ->
                            Text(
                                "광고: " + monitorErrorLabel(error),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            item {
                Button(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("기존 재난문자 선택 정리")
                }
            }
            item {
                Button(onClick = onOpenAdHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("기존 광고 문자 정리")
                }
            }
            item {
                OutlinedButton(onClick = onOpenAdLog, modifier = Modifier.fillMaxWidth()) {
                    Text("광고 삭제 이력")
                }
            }
            item {
                Text(
                    "자동 정리는 각 기능을 켠 뒤 새로 수신되는 문자에만 적용됩니다. " +
                        "그 전 문자는 위의 정리 화면에서 직접 지웁니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FeatureSwitchCard(
    title: String,
    enabled: Boolean,
    isMonitoring: Boolean,
    enabledText: String,
    disabledText: String,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEnabledChange(!enabled) },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isMonitoring) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (isMonitoring) "감시 중" else if (enabled) "설정 확인 필요" else "기능 꺼짐",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(text = if (enabled) enabledText else disabledText)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}
```

- [ ] **Step 2: MainActivity 전체 교체**

```kotlin
package com.interbb.disasterinboxcleaner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.interbb.disasterinboxcleaner.ui.AdLogScreen
import com.interbb.disasterinboxcleaner.ui.HistoryScreen
import com.interbb.disasterinboxcleaner.ui.HomeScreen
import com.interbb.disasterinboxcleaner.ui.OnboardingScreen
import com.interbb.disasterinboxcleaner.ui.SettingsScreen
import com.interbb.disasterinboxcleaner.ui.theme.DisasterInboxCleanerTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DisasterInboxCleanerTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val historyState by viewModel.historyState.collectAsStateWithLifecycle()
                val adLogEntries by viewModel.adLogState.collectAsStateWithLifecycle()
                var screenName by rememberSaveable {
                    mutableStateOf(
                        if (state.onboardingComplete) AppScreen.HOME.name else AppScreen.ONBOARDING.name,
                    )
                }
                val screen = AppScreen.valueOf(screenName)
                val adbCommand = "adb shell appops set $packageName WRITE_SMS allow"

                val smsPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { viewModel.refreshAndRequestRebind() }

                LaunchedEffect(state.onboardingComplete) {
                    if (!state.onboardingComplete) screenName = AppScreen.ONBOARDING.name
                }

                fun copyAdbCommand() {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("ADB 삭제 권한", adbCommand),
                    )
                    Toast.makeText(this@MainActivity, "ADB 명령을 복사했습니다", Toast.LENGTH_SHORT).show()
                }

                val requestSmsPermission = {
                    smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
                }
                val openNotificationAccess = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                val openMessagingApp = {
                    startActivity(
                        Intent.makeMainSelectorActivity(
                            Intent.ACTION_MAIN,
                            Intent.CATEGORY_APP_MESSAGING,
                        ),
                    )
                }
                val goHome = {
                    if (screen == AppScreen.HISTORY) viewModel.clearHistoryFromMemory()
                    screenName = AppScreen.HOME.name
                }

                BackHandler(enabled = screen != AppScreen.HOME && screen != AppScreen.ONBOARDING) {
                    goHome()
                }

                when (screen) {
                    AppScreen.ONBOARDING -> OnboardingScreen(
                        state = state,
                        onRequestSmsPermission = requestSmsPermission,
                        onOpenNotificationAccess = openNotificationAccess,
                        adbCommand = adbCommand,
                        onCopyAdbCommand = ::copyAdbCommand,
                        onRefresh = viewModel::refreshAndRequestRebind,
                        onComplete = {
                            if (viewModel.completeOnboarding()) {
                                screenName = AppScreen.HOME.name
                            }
                        },
                    )
                    AppScreen.HOME -> HomeScreen(
                        state = state,
                        onEnabledChange = viewModel::setEnabled,
                        onAdEnabledChange = viewModel::setAdEnabled,
                        onOpenHistory = {
                            screenName = AppScreen.HISTORY.name
                            viewModel.loadHistory(HistorySource.DISASTER)
                        },
                        onOpenAdHistory = {
                            screenName = AppScreen.HISTORY.name
                            viewModel.loadHistory(HistorySource.AD)
                        },
                        onOpenAdLog = {
                            screenName = AppScreen.AD_LOG.name
                            viewModel.loadAdLog()
                        },
                        onOpenSettings = { screenName = AppScreen.SETTINGS.name },
                    )
                    AppScreen.HISTORY -> HistoryScreen(
                        state = historyState,
                        canDelete = state.smsDeleteGranted,
                        onBack = goHome,
                        onRefresh = { viewModel.loadHistory() },
                        onToggleSelection = viewModel::toggleHistorySelection,
                        onToggleSelectAll = viewModel::toggleSelectAllHistory,
                        onDeleteSelected = viewModel::deleteSelectedHistory,
                        onDeleteAll = viewModel::deleteAllHistory,
                        onOpenMessagingApp = openMessagingApp,
                    )
                    AppScreen.AD_LOG -> AdLogScreen(
                        entries = adLogEntries,
                        onBack = goHome,
                        onRefresh = viewModel::loadAdLog,
                        onClear = viewModel::clearAdLog,
                    )
                    AppScreen.SETTINGS -> SettingsScreen(
                        state = state,
                        onBack = goHome,
                        onRequestSmsPermission = requestSmsPermission,
                        onOpenNotificationAccess = openNotificationAccess,
                        adbCommand = adbCommand,
                        onCopyAdbCommand = ::copyAdbCommand,
                        onOpenAppSettings = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        },
                        onRefresh = viewModel::refreshAndRequestRebind,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAndRequestRebind()
    }

    private enum class AppScreen {
        ONBOARDING,
        HOME,
        HISTORY,
        AD_LOG,
        SETTINGS,
    }
}
```

- [ ] **Step 3: 온보딩 문구 2곳 수정**

`OnboardingScreen.kt` 55-58행의 `Text(...)` 내용을:

```kotlin
                        Text(
                            "이 앱은 삼성 메시지함에 복사되는 #CMAS# 재난문자와, 켜 두면 " +
                                "(광고)/[광고]로 시작하는 광고 문자를 정리합니다. " +
                                "재난문자의 시스템 알림·경고음과 안전 및 긴급의 원본 기록은 유지됩니다.",
                        )
```

75-76행의 `explanation`을:

```kotlin
                    explanation = "#CMAS# 복사본과 광고 문자를 찾는 데 필요합니다. " +
                        "재난문자 감시는 본문을 조회하지 않고, 광고 판정은 본문 앞부분만 확인합니다.",
```

- [ ] **Step 4: 컴파일과 전체 테스트 확인**

Run:
```bash
./gradlew --offline testDebugUnitTest assembleDebug 2>&1 | grep -E "^e: |FAILED|BUILD" | head
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/interbb/disasterinboxcleaner/ui/HomeScreen.kt app/src/main/java/com/interbb/disasterinboxcleaner/MainActivity.kt app/src/main/java/com/interbb/disasterinboxcleaner/ui/OnboardingScreen.kt
git commit -q -m "feat: ad cleanup switch, screens and navigation"
```

---

### Task 10: 이름·버전·README, 빌드·설치, 실기기 E2E

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/build.gradle.kts:15-16`
- Modify: `README.md` (전체 교체)

- [ ] **Step 1: 앱 이름과 버전**

`app/src/main/res/values/strings.xml` 전체:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">문자함 정리</string>
    <string name="notification_access_label">문자함 감시</string>
</resources>
```

`app/build.gradle.kts`의 `versionCode = 4` → `versionCode = 5`, `versionName = "1.0.3"` → `versionName = "1.1.0"`.

- [ ] **Step 2: README 전체 교체**

````markdown
# 문자함 정리 (DisasterInboxCleaner)

삼성 메시지를 기본 문자 앱으로 둔 채, 삼성 메시지함에서 두 종류의 문자를 자동으로 지우는 개인용 사이드로드 앱입니다.

- 한국형 Android Cell Broadcast가 메시지함에 만드는 `#CMAS#` 재난문자 복사본
- 본문이 `(광고)` 또는 `[광고]`로 시작하는 광고 문자 (정보통신망법 의무 표기)

## 보존되는 항목

- 재난문자 수신 알림, 팝업, 경고음
- `설정 > 안전 및 긴급`의 Cell Broadcast 원본 기록
- 일반 SMS/MMS와 기존 메시지

## 프라이버시

- 재난문자 경로는 발신 주소(`#CMAS#`)만 보고 본문을 조회하거나 저장하지 않습니다.
- 광고 문자 경로는 판정을 위해 본문 앞부분을 읽고, 삭제 이력에 시각·발신번호·본문 앞 30자만 앱 내부 파일(`files/ad_cleanup_history.tsv`)에 남깁니다. 최근 300건, 30일까지 보관하며 전체 본문은 어디에도 저장하지 않습니다.
- 인터넷 권한을 선언하지 않습니다.
- 기존 문자 정리 화면은 사용자가 항목을 고를 수 있도록 본문을 기기 메모리에만 잠시 표시하고, 화면을 나가면 목록을 비웁니다.

## 빌드

프로젝트에 로컬 Android SDK가 `.android-sdk`로 구성되어 있습니다.

```bash
GRADLE_USER_HOME="$PWD/.gradle-user" ANDROID_USER_HOME="$PWD/.android-user" ./gradlew --offline testDebugUnitTest assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 설치와 최초 설정

이 앱은 Google Play 배포용이 아니라 개인용 사이드로드 앱입니다. 메시지함 삭제 권한(WRITE_SMS appops)은 기본 문자 앱이 아닌 앱에 줄 수 있는 설정 화면이 없어서 PC의 adb로 한 번 부여해야 합니다. 설치당 한 번이면 되고 재부팅해도 유지되며, 앱을 지웠다 다시 깔면 다시 부여합니다.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.interbb.disasterinboxcleaner WRITE_SMS allow
```

문자 읽기 권한은 처음 설정 화면의 `문자 권한 요청`으로 앱 안에서 요청합니다. 대화상자가 뜨지 않으면 adb로 부여합니다.

```bash
adb shell pm grant com.interbb.disasterinboxcleaner android.permission.READ_SMS
```

알림 접근은 처음 설정 화면의 `시스템 알림 접근 화면 열기`에서 **문자함 감시**를 허용합니다. 세 권한이 모두 승인되면 설정을 완료하고 홈에서 각 기능을 켭니다.

권한을 되돌리려면:

```bash
adb shell appops set com.interbb.disasterinboxcleaner WRITE_SMS default
adb shell pm revoke com.interbb.disasterinboxcleaner android.permission.READ_SMS
```

## 백그라운드 동작

사용자가 알림 접근을 허용하면 Android가 `NotificationListenerService`를 관리합니다. 앱은 재난문자 알림·삼성 메시지 알림과 SMS 저장소 변경을 감지하고 수신 직후 여러 차례 제한적으로 정리를 시도합니다. 재부팅 후에도 기능이 켜져 있으면 서비스 재연결을 요청합니다. 별도의 상시 알림이나 포그라운드 서비스는 만들지 않습니다.

광고 문자를 지운 뒤에는 본문이 `(광고)`/`[광고]`로 시작하는 삼성 메시지 알림을 닫습니다. 재난문자 알림은 닫지 않습니다.

이 구현은 현재 확인한 Samsung Galaxy Fold8(SM-F971N)의 Android 17 / One UI 9 구성(`enable_write_alerts_to_sms_inbox=true`, `#CMAS#` 발신 주소, 기본 문자 앱 삼성 메시지)에 맞춰져 있습니다.

## 기존 문자 정리

홈의 `기존 재난문자 선택 정리`와 `기존 광고 문자 정리`에서 이미 쌓인 문자를 지웁니다. 실제 삭제 전 확인창이 표시됩니다.

- 삭제 요청은 `content://sms` 경로로 보냅니다. `content://sms/inbox` 경로는 Android 저장소가 삭제를 거부하고(1.0.2까지의 결함), `content://sms/N` 경로는 where절을 무시합니다.
- Android는 기본 문자 앱이 아닌 앱에 제한된 조회 목록을 제공하고, 이 기기는 그 위에 재난문자 행을 숨깁니다. 그래서 재난문자는 목록 없이 전체 삭제만 되고, 광고 문자는 목록에서 골라 지울 수 있습니다.

## 한계

- 수신음·진동과 잠깐 뜨는 알림은 기본 문자 앱이 아니라 막을 수 없습니다.
- `(광고)`/`[광고]` 표기 없는 스팸, MMS·RCS 광고, 삼성 스팸함으로 이미 이동한 문자는 대상이 아닙니다.
- 알림 패키지는 삼성 메시지 하나입니다. 기본 문자 앱이 바뀌면 상수 한 곳(`DisasterNotificationListenerService.SAMSUNG_MESSAGES_PACKAGE`)을 바꿔야 합니다.
````

- [ ] **Step 3: 전체 테스트·빌드**

Run:
```bash
./gradlew --offline testDebugUnitTest assembleDebug 2>&1 | grep -E "^e: |FAILED|BUILD" | head
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
for f in sorted(glob.glob('app/build/test-results/testDebugUnitTest/*.xml')):
    r=ET.parse(f).getroot(); print(r.get('name'), 'tests', r.get('tests'), 'failures', r.get('failures'), 'errors', r.get('errors'))
EOF
```
Expected: `BUILD SUCCESSFUL`; 테스트 클래스 4개(`AdSmsPolicyTest` 3, `AdCleanupHistoryTest` 6, `EmergencyCopyPolicyTest` 2, `HistoryUiStateTest` 2), failures 0, errors 0.

- [ ] **Step 4: 설치와 권한**

폰이 adb에 연결돼 있어야 한다(`adb devices`에 `device`).

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.interbb.disasterinboxcleaner WRITE_SMS allow
adb shell dumpsys package com.interbb.disasterinboxcleaner | grep -E "versionName|READ_SMS: granted"
adb shell cmd role get-role-holders android.app.role.SMS
```
Expected: `versionName=1.1.0`, `READ_SMS: granted=true`(업데이트 설치라 유지), 역할 홀더 `com.samsung.android.messaging`.

- [ ] **Step 5: 실기기 E2E (사용자 조작 + adb 확인)**

사용자에게 부탁할 순서와 각 단계의 adb 확인:

1. 앱 열기 → 홈 제목이 "문자함 정리", 카드 두 개(재난문자 복사본 정리 / 광고 문자 자동 삭제) 표시.
2. "광고 문자 자동 삭제" 켜기 → 카드가 "감시 중".
3. 다른 폰 또는 웹문자로 Fold8에 `(광고) 테스트 문자` 발송.
4. 확인:
   ```bash
   adb shell content query --uri content://sms/inbox --projection _id:date --where "\"body LIKE '(광고)%'\"" | grep -c "^Row:"
   adb shell "run-as com.interbb.disasterinboxcleaner cat files/ad_cleanup_history.tsv" 2>/dev/null || echo "(run-as 불가: 앱의 광고 삭제 이력 화면에서 확인)"
   ```
   Expected: 첫 명령 `0`(행이 지워짐). 이력 화면에 시각·발신번호·`(광고) 테스트 문자`·자동 삭제 1건. 알림이 닫혔는지는 사용자가 알림 창에서 확인.
5. "광고 문자 자동 삭제" 끄기 → 같은 문자를 한 번 더 받기 → 홈 "기존 광고 문자 정리" → 목록에 발신번호·본문이 보이고 "메시지함의 광고 문자 모두 삭제" → 확인 → 목록 비고 이력에 수동 삭제 1건. adb 첫 명령 다시 `0`.
6. 회귀: "기존 재난문자 선택 정리" 화면이 이전과 같이 열리고(목록 제한 카드), 설정 화면 adb 안내가 그대로인지 확인.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/values/strings.xml app/build.gradle.kts README.md
git commit -q -m "chore: rename to 문자함 정리, bump 1.1.0, document ad cleanup"
```

---

## 스펙 대비 점검 (작성자 자체 검토)

- §2 목표 5개 → Task 5(자동 삭제·알림 닫기), Task 2·8(이력 목록), Task 4·7·9(기존 광고 정리), Task 10(이름).
- §4.1 새 파일 4개 → Task 1·2·3·4. §4.2 수정 파일 → Task 5(서비스), 6(ViewModel), 9(Home·MainActivity·Onboarding), 7(HistoryScreen), 8(AdLogScreen), 10(strings·gradle·README).
- §5 데이터 흐름 → Task 5. §6 권한 → 변경 없음(README만, Task 10). §7 오류 처리 → Task 4(예외 매핑·행별 삭제·성공만 기록), Task 2(손상 파일), Task 5(알림 닫기 조건).
- §8 테스트 → Task 1·2·6의 JVM 테스트, Task 10의 E2E.
- §9 한계 → README(Task 10).
- 타입 일치: `AdCleanupEntry(at, address, snippet, rule)`는 Task 2·4·8에서 같은 순서/이름. `HistoryLoadResult`·`ManualDeleteResult`는 기존 타입 재사용. `loadHistory(source)` 기본값 덕분에 `HistoryScreen.onRefresh = { viewModel.loadHistory() }`가 현재 source를 유지한다.
