# 내장 ADB 자가 권한 부여 구현 계획 ("문자함 정리" v1.2.0)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PC 없이 폰 혼자서 `WRITE_SMS` 앱옵을 받도록, 동봉 adb 바이너리 + 무선 디버깅 페어링 + 알림 인라인 코드 입력 + 컴맹용 안내 화면을 앱에 넣는다.

**Architecture:** 순수 Kotlin 층(`AdbOutcomes`, `PortSelector`, `AdbGrantMachine`: JVM 테스트) 위에 Android 층(`AdbBinary` 실행 래퍼, `AdbPortDiscovery` mDNS, `AdbGrantNotifications`, `AdbCodeReceiver`, `AdbGrantService` 포그라운드 상태 기계)을 얹고, UI는 온보딩/설정 카드 교체와 `GuideScreen`(5단계, 캡처 이미지) 추가. 기존 광고·재난문자 로직은 건드리지 않는다.

**Tech Stack:** Kotlin, Jetpack Compose + Material3, AGP 9.1.1 내장 Kotlin, Gradle 9.3.1, JUnit 4, adb(동봉 `libadb.so`, Apache-2.0), NsdManager, Python 3 + Pillow(이미지 주석). 의존성 추가 없음.

**Spec:** `docs/superpowers/specs/2026-09-16-adb-self-grant-design.md`

## Global Constraints

- 프로젝트 루트: `/Users/interbb/workspace/private/intellij/DisasterInboxCleaner`. git 저장소가 아니다. **git 명령 금지.** 각 태스크의 완료 조건은 그 직전 단계의 빌드·테스트 통과다.
- 빌드 환경(모든 gradle 명령 앞에 붙인다):
  ```bash
  cd /Users/interbb/workspace/private/intellij/DisasterInboxCleaner && export JAVA_HOME=/Users/interbb/Library/Java/JavaVirtualMachines/ms-17.0.15/Contents/Home GRADLE_USER_HOME="$PWD/.gradle-user" ANDROID_USER_HOME="$PWD/.android-user"
  ```
  gradle은 항상 `--offline`. 의존성 추가 금지.
- 패키지 `com.interbb.disasterinboxcleaner` 유지. 새 코드는 하위 패키지 `com.interbb.disasterinboxcleaner.adb` (파일 경로 `app/src/main/java/com/interbb/disasterinboxcleaner/adb/`). `minSdk = 30`, compileSdk/targetSdk 37.
- 동봉 바이너리는 이미 `app/src/main/jniLibs/arm64-v8a/libadb.so`에 있고 라이선스는 `app/src/main/assets/licenses/adb-LICENSE.txt`, 출처 메모는 `adb-NOTICE.txt`. 바꾸지 않는다.
- adb 서버 포트는 환경변수 `ANDROID_ADB_SERVER_PORT=5038`. 모든 adb 실행은 `AdbBinary.run`을 통해서만. 셸 명령 인자는 리스트로 분리해 넘긴다.
- 페어링 코드·포트는 로그·prefs·파일 어디에도 저장하지 않는다. 출력은 `AdbOutcomes.maskCode` 후에만 로그·화면에 쓴다.
- 안내 문구는 한국어, 한 화면 한 동작, 화면에 보이는 글자는 따옴표. 전문용어는 괄호 설명.
- 새 파일에 `TODO`·`FIXME`·미구현 분기 금지.
- 폰 조작이 필요한 태스크(5, 7)는 컨트롤러가 사용자와 함께 진행한다. 실행자는 폰에 손대지 않는다(adb 금지), 명시된 경우 제외.

---

### Task 1: 빌드 설정·매니페스트 권한 + AdbBinary + AdbOutcomes

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/adb/AdbBinary.kt`
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/adb/AdbOutcomes.kt`
- Test: `app/src/test/java/com/interbb/disasterinboxcleaner/adb/AdbOutcomesTest.kt`

**Interfaces:**
- Produces: `data class AdbResult(exitCode: Int, output: String)`; `class AdbBinary(context)` with `isAvailable()`, `run(args, timeoutSeconds)`, `pair(port, code)`, `connect(port)`, `shell(vararg cmd)`, `killServer()`; `object AdbOutcomes` with `pairSucceeded`, `connectSucceeded`, `grantSucceeded`, `maskCode`.

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/java/com/interbb/disasterinboxcleaner/adb/AdbOutcomesTest.kt`:
```kotlin
package com.interbb.disasterinboxcleaner.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbOutcomesTest {
    @Test
    fun pairSucceedsOnlyWithExitZeroAndSuccessLine() {
        assertTrue(AdbOutcomes.pairSucceeded(AdbResult(0, "Successfully paired to 127.0.0.1:34103 [guid=adb-X]")))
        assertFalse(AdbOutcomes.pairSucceeded(AdbResult(0, "Failed: Wrong password or connection was dropped.")))
        assertFalse(AdbOutcomes.pairSucceeded(AdbResult(1, "Successfully paired")))
    }

    @Test
    fun connectSucceedsOnConnectedOrAlreadyConnected() {
        assertTrue(AdbOutcomes.connectSucceeded(AdbResult(0, "connected to 127.0.0.1:46069")))
        assertTrue(AdbOutcomes.connectSucceeded(AdbResult(0, "already connected to 127.0.0.1:46069")))
        assertFalse(AdbOutcomes.connectSucceeded(AdbResult(0, "failed to connect to '127.0.0.1:46069': Connection refused")))
        assertFalse(AdbOutcomes.connectSucceeded(AdbResult(-1, "timeout")))
    }

    @Test
    fun grantSucceedsOnExitZeroWithoutErrorText() {
        assertTrue(AdbOutcomes.grantSucceeded(AdbResult(0, "")))
        assertFalse(AdbOutcomes.grantSucceeded(AdbResult(0, "Error: Unknown package: com.x")))
        assertFalse(AdbOutcomes.grantSucceeded(AdbResult(0, "java.lang.SecurityException: uid 10556 ...")))
        assertFalse(AdbOutcomes.grantSucceeded(AdbResult(255, "AppOps service (appops) commands:")))
    }

    @Test
    fun maskCodeHidesSixDigitRunsOnly() {
        assertEquals("pair 127.0.0.1:34103 ******", AdbOutcomes.maskCode("pair 127.0.0.1:34103 440184"))
        assertEquals("port 46069 ok", AdbOutcomes.maskCode("port 46069 ok"))
        assertEquals("id 1234567", AdbOutcomes.maskCode("id 1234567"))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew --offline testDebugUnitTest --tests 'com.interbb.disasterinboxcleaner.adb.AdbOutcomesTest' 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `Unresolved reference 'AdbOutcomes'`, `BUILD FAILED`.

- [ ] **Step 3: build.gradle.kts**

`app/build.gradle.kts`의 `defaultConfig`를 다음으로 바꾼다(다른 블록은 그대로):
```kotlin
    defaultConfig {
        applicationId = "com.interbb.disasterinboxcleaner"
        minSdk = 30
        targetSdk = 37
        versionCode = 8
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
```
그리고 기존 `packaging { resources { ... } }` 블록 안, `resources { }` 다음 줄에 추가:
```kotlin
        jniLibs { useLegacyPackaging = true }
```
(결과: `packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }; jniLibs { useLegacyPackaging = true } }` 형태.)

- [ ] **Step 4: AndroidManifest.xml**

`<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />` 아래에 추가:
```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
`<application` 태그에 속성 추가: `android:extractNativeLibs="true"`.
(서비스·리시버 선언은 Task 3에서 클래스와 함께 추가한다.)

- [ ] **Step 5: AdbBinary.kt**

```kotlin
package com.interbb.disasterinboxcleaner.adb

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AdbResult(val exitCode: Int, val output: String)

/**
 * Runs the bundled adb client (app/src/main/jniLibs/arm64-v8a/libadb.so, Apache-2.0 AOSP build).
 * The binary lives in the app's native library dir, the only location Android lets an app execute
 * from. HOME/TMPDIR point inside the app so pairing keys stay private, and the adb server uses
 * port 5038 to avoid clashing with other on-device adb servers.
 */
class AdbBinary(private val context: Context) {
    val file: File
        get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    fun isAvailable(): Boolean = file.exists() && file.canExecute()

    fun run(args: List<String>, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): AdbResult {
        val home = File(context.filesDir, "adbhome").apply { mkdirs() }
        val tmp = File(context.cacheDir, "adbtmp").apply { mkdirs() }
        val builder = ProcessBuilder(listOf(file.absolutePath) + args).redirectErrorStream(true)
        builder.environment()["HOME"] = home.absolutePath
        builder.environment()["TMPDIR"] = tmp.absolutePath
        builder.environment()["ANDROID_ADB_SERVER_PORT"] = SERVER_PORT.toString()
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return AdbResult(-1, "launch failed: ${e.message}")
        }
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return AdbResult(-1, "timeout")
        }
        return AdbResult(process.exitValue(), output)
    }

    fun pair(port: Int, code: String): AdbResult = run(listOf("pair", "127.0.0.1:$port", code))

    fun connect(port: Int): AdbResult = run(listOf("connect", "127.0.0.1:$port"))

    fun shell(vararg command: String): AdbResult = run(listOf("shell") + command)

    fun killServer(): AdbResult = run(listOf("kill-server"), timeoutSeconds = 10)

    companion object {
        const val BINARY_NAME = "libadb.so"
        const val SERVER_PORT = 5038
        const val DEFAULT_TIMEOUT_SECONDS = 30L
    }
}
```

- [ ] **Step 6: AdbOutcomes.kt**

```kotlin
package com.interbb.disasterinboxcleaner.adb

/** Interprets adb client output. Pure Kotlin so it is unit-tested without Android. */
object AdbOutcomes {
    private val sixDigits = Regex("(?<!\\d)\\d{6}(?!\\d)")

    fun pairSucceeded(result: AdbResult): Boolean =
        result.exitCode == 0 && result.output.contains("Successfully paired")

    fun connectSucceeded(result: AdbResult): Boolean =
        result.exitCode == 0 &&
            (result.output.contains("connected to") || result.output.contains("already connected to")) &&
            !result.output.contains("failed to connect")

    fun grantSucceeded(result: AdbResult): Boolean =
        result.exitCode == 0 &&
            !result.output.contains("Error", ignoreCase = true) &&
            !result.output.contains("Exception")

    /** Hides any standalone 6-digit run (a pairing code) before text is logged or shown. */
    fun maskCode(text: String): String = sixDigits.replace(text, "******")
}
```

- [ ] **Step 7: 통과 확인**

Run: `./gradlew --offline testDebugUnitTest --tests 'com.interbb.disasterinboxcleaner.adb.AdbOutcomesTest' assembleDebug 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`. 추가 확인: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -c "lib/arm64-v8a/libadb.so"` → `1`.

---

### Task 2: PortSelector, AdbGrantState(prefs), AdbGrantMachine (+ JVM 테스트 2개)

**Files:**
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/adb/PortSelector.kt`
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/adb/AdbGrantState.kt`
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/adb/AdbGrantMachine.kt`
- Test: `app/src/test/java/com/interbb/disasterinboxcleaner/adb/PortSelectorTest.kt`
- Test: `app/src/test/java/com/interbb/disasterinboxcleaner/adb/AdbGrantMachineTest.kt`

**Interfaces:**
- Produces: `data class Advert(name, port, seenAt)`, `class PortSelector { offer(Advert); best(now, maxAgeMs=60_000): Int?; clear() }`; `enum class GrantPhase { IDLE, WAITING_CODE, PAIRING, CONNECTING, GRANTING, DONE, FAILED }`; `data class GrantStatus(phase, message, updatedAt)` with `inProgress`; `class AdbGrantPreferences(context)` with `snapshot()`, `set(phase, message)`, `registerListener`, `unregisterListener`; `sealed interface GrantEvent`; `data class Transition(phase, message)`; `object AdbGrantMachine { next(current, event): Transition? }`; `object GrantMessages`.

- [ ] **Step 1: 실패하는 테스트 작성**

`PortSelectorTest.kt`:
```kotlin
package com.interbb.disasterinboxcleaner.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortSelectorTest {
    @Test
    fun newestAdvertWins() {
        val selector = PortSelector()
        selector.offer(Advert("adb-a", 40001, seenAt = 1_000))
        selector.offer(Advert("adb-b", 40002, seenAt = 2_000))
        assertEquals(40002, selector.best(now = 2_500))
    }

    @Test
    fun zeroPortsAndStaleAdvertsAreIgnored() {
        val selector = PortSelector()
        selector.offer(Advert("adb-a", 0, seenAt = 5_000))
        assertNull(selector.best(now = 5_000))
        selector.offer(Advert("adb-b", 40003, seenAt = 1_000))
        assertNull(selector.best(now = 100_000, maxAgeMs = 60_000))
        assertEquals(40003, selector.best(now = 50_000, maxAgeMs = 60_000))
    }

    @Test
    fun emptySelectorReturnsNull() {
        assertNull(PortSelector().best(now = 0))
    }
}
```

`AdbGrantMachineTest.kt`:
```kotlin
package com.interbb.disasterinboxcleaner.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbGrantMachineTest {
    @Test
    fun happyPathWalksEveryPhase() {
        var phase = GrantPhase.IDLE
        fun step(event: GrantEvent): Transition {
            val t = AdbGrantMachine.next(phase, event)!!
            phase = t.phase
            return t
        }
        assertEquals(GrantPhase.WAITING_CODE, step(GrantEvent.Start).phase)
        assertEquals(GrantPhase.PAIRING, step(GrantEvent.CodeEntered).phase)
        assertEquals(GrantPhase.CONNECTING, step(GrantEvent.PairResult(true)).phase)
        assertEquals(GrantPhase.GRANTING, step(GrantEvent.ConnectResult(true)).phase)
        val done = step(GrantEvent.GrantResult(verified = true, detail = ""))
        assertEquals(GrantPhase.DONE, done.phase)
        assertEquals(GrantMessages.DONE, done.message)
    }

    @Test
    fun failuresLandInFailedWithTheirMessage() {
        assertEquals(GrantMessages.BINARY_MISSING, AdbGrantMachine.next(GrantPhase.IDLE, GrantEvent.BinaryMissing)!!.message)
        assertEquals(GrantMessages.WIRELESS_OFF, AdbGrantMachine.next(GrantPhase.IDLE, GrantEvent.WirelessDebuggingOff)!!.message)
        assertEquals(GrantMessages.NO_WIFI, AdbGrantMachine.next(GrantPhase.IDLE, GrantEvent.NoWifi)!!.message)
        assertEquals(GrantMessages.PAIRING_PORT_MISSING, AdbGrantMachine.next(GrantPhase.PAIRING, GrantEvent.PairingPortMissing)!!.message)
        assertEquals(GrantMessages.PAIR_FAILED, AdbGrantMachine.next(GrantPhase.PAIRING, GrantEvent.PairResult(false))!!.message)
        assertEquals(GrantMessages.CONNECT_FAILED, AdbGrantMachine.next(GrantPhase.CONNECTING, GrantEvent.ConnectResult(false))!!.message)
        assertEquals(GrantMessages.CONNECT_FAILED, AdbGrantMachine.next(GrantPhase.CONNECTING, GrantEvent.ConnectPortMissing)!!.message)
        val grantFail = AdbGrantMachine.next(GrantPhase.GRANTING, GrantEvent.GrantResult(false, "Error: Unknown package"))!!
        assertEquals(GrantPhase.FAILED, grantFail.phase)
        assertTrue(grantFail.message.contains("Error: Unknown package"))
        assertEquals(GrantMessages.TIMEOUT, AdbGrantMachine.next(GrantPhase.WAITING_CODE, GrantEvent.Timeout)!!.message)
    }

    @Test
    fun cancelReturnsToIdleAndUnexpectedEventsAreIgnored() {
        assertEquals(GrantPhase.IDLE, AdbGrantMachine.next(GrantPhase.WAITING_CODE, GrantEvent.Cancel)!!.phase)
        assertNull(AdbGrantMachine.next(GrantPhase.IDLE, GrantEvent.CodeEntered))
        assertNull(AdbGrantMachine.next(GrantPhase.DONE, GrantEvent.PairResult(true)))
        assertNull(AdbGrantMachine.next(GrantPhase.PAIRING, GrantEvent.CodeEntered))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew --offline testDebugUnitTest --tests 'com.interbb.disasterinboxcleaner.adb.PortSelectorTest' --tests 'com.interbb.disasterinboxcleaner.adb.AdbGrantMachineTest' 2>&1 | grep -E "^e: |BUILD" | head -5`
Expected: `Unresolved reference`, `BUILD FAILED`.

- [ ] **Step 3: PortSelector.kt**

```kotlin
package com.interbb.disasterinboxcleaner.adb

data class Advert(val name: String, val port: Int, val seenAt: Long)

/** Keeps the freshest mDNS advertisement per service name and picks the newest usable port. */
class PortSelector {
    private val adverts = LinkedHashMap<String, Advert>()

    @Synchronized
    fun offer(advert: Advert) {
        if (advert.port <= 0) return
        val existing = adverts[advert.name]
        if (existing == null || existing.seenAt <= advert.seenAt) adverts[advert.name] = advert
    }

    @Synchronized
    fun best(now: Long, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Int? =
        adverts.values
            .filter { now - it.seenAt <= maxAgeMs }
            .maxByOrNull { it.seenAt }
            ?.port

    @Synchronized
    fun clear() = adverts.clear()

    companion object {
        const val DEFAULT_MAX_AGE_MS = 60_000L
    }
}
```

- [ ] **Step 4: AdbGrantState.kt**

```kotlin
package com.interbb.disasterinboxcleaner.adb

import android.content.Context
import android.content.SharedPreferences

enum class GrantPhase {
    IDLE,
    WAITING_CODE,
    PAIRING,
    CONNECTING,
    GRANTING,
    DONE,
    FAILED,
}

data class GrantStatus(
    val phase: GrantPhase = GrantPhase.IDLE,
    val message: String = "",
    val updatedAt: Long = 0L,
) {
    val inProgress: Boolean
        get() = phase == GrantPhase.WAITING_CODE || phase == GrantPhase.PAIRING ||
            phase == GrantPhase.CONNECTING || phase == GrantPhase.GRANTING
}

/** Last known state of the on-phone grant flow, shared by the service and the UI. */
class AdbGrantPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): GrantStatus = GrantStatus(
        phase = preferences.getString(KEY_PHASE, null)
            ?.let { stored -> GrantPhase.entries.firstOrNull { it.name == stored } }
            ?: GrantPhase.IDLE,
        message = preferences.getString(KEY_MESSAGE, "").orEmpty(),
        updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L),
    )

    fun set(phase: GrantPhase, message: String) {
        preferences.edit()
            .putString(KEY_PHASE, phase.name)
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val PREFS_NAME = "adb_grant_state"
        const val KEY_PHASE = "phase"
        const val KEY_MESSAGE = "message"
        const val KEY_UPDATED_AT = "updated_at"
    }
}
```

- [ ] **Step 5: AdbGrantMachine.kt**

```kotlin
package com.interbb.disasterinboxcleaner.adb

/** User-facing messages for every phase and failure. Single source so the UI and notification agree. */
object GrantMessages {
    const val WAITING = "설정의 페어링 창을 열어 두고, 알림에 6자리 코드를 입력하세요."
    const val PAIRING = "페어링 중…"
    const val CONNECTING = "연결 중…"
    const val GRANTING = "권한 부여 중…"
    const val DONE = "완료. 이제 무선 디버깅을 꺼도 됩니다."
    const val CANCELLED = "취소했습니다."
    const val BINARY_MISSING = "이 기기에서는 지원되지 않습니다. 아래 'PC에서 adb' 방법을 쓰세요."
    const val WIRELESS_OFF = "무선 디버깅이 꺼져 있습니다. 2단계를 확인하세요."
    const val NO_WIFI = "Wi-Fi에 연결한 뒤 다시 시도하세요."
    const val PAIRING_PORT_MISSING = "페어링 창이 열려 있는지 확인하세요. 안 되면 '포트 직접 입력'을 쓰세요."
    const val PAIR_FAILED = "코드가 틀렸거나 창이 닫혔습니다. 3단계부터 다시 하세요."
    const val CONNECT_FAILED = "연결에 실패했습니다. 무선 디버깅을 껐다 켜고 다시 시도하세요."
    const val GRANT_FAILED_PREFIX = "권한 부여에 실패했습니다: "
    const val TIMEOUT = "시간이 지나 중단했습니다. 다시 시도하세요."
}

sealed interface GrantEvent {
    data object Start : GrantEvent
    data object BinaryMissing : GrantEvent
    data object WirelessDebuggingOff : GrantEvent
    data object NoWifi : GrantEvent
    data object CodeEntered : GrantEvent
    data object PairingPortMissing : GrantEvent
    data class PairResult(val ok: Boolean) : GrantEvent
    data object ConnectPortMissing : GrantEvent
    data class ConnectResult(val ok: Boolean) : GrantEvent
    data class GrantResult(val verified: Boolean, val detail: String) : GrantEvent
    data object Timeout : GrantEvent
    data object Cancel : GrantEvent
}

data class Transition(val phase: GrantPhase, val message: String)

/** Pure state machine for the grant flow; returns null when the event does not apply to the phase. */
object AdbGrantMachine {
    fun next(current: GrantPhase, event: GrantEvent): Transition? {
        if (event is GrantEvent.Cancel) return Transition(GrantPhase.IDLE, GrantMessages.CANCELLED)
        return when (current) {
            GrantPhase.IDLE, GrantPhase.DONE, GrantPhase.FAILED -> when (event) {
                GrantEvent.Start -> Transition(GrantPhase.WAITING_CODE, GrantMessages.WAITING)
                GrantEvent.BinaryMissing -> failed(GrantMessages.BINARY_MISSING)
                GrantEvent.WirelessDebuggingOff -> failed(GrantMessages.WIRELESS_OFF)
                GrantEvent.NoWifi -> failed(GrantMessages.NO_WIFI)
                else -> null
            }
            GrantPhase.WAITING_CODE -> when (event) {
                GrantEvent.CodeEntered -> Transition(GrantPhase.PAIRING, GrantMessages.PAIRING)
                GrantEvent.Timeout -> failed(GrantMessages.TIMEOUT)
                GrantEvent.WirelessDebuggingOff -> failed(GrantMessages.WIRELESS_OFF)
                else -> null
            }
            GrantPhase.PAIRING -> when (event) {
                GrantEvent.PairingPortMissing -> failed(GrantMessages.PAIRING_PORT_MISSING)
                is GrantEvent.PairResult ->
                    if (event.ok) Transition(GrantPhase.CONNECTING, GrantMessages.CONNECTING) else failed(GrantMessages.PAIR_FAILED)
                else -> null
            }
            GrantPhase.CONNECTING -> when (event) {
                GrantEvent.ConnectPortMissing -> failed(GrantMessages.CONNECT_FAILED)
                is GrantEvent.ConnectResult ->
                    if (event.ok) Transition(GrantPhase.GRANTING, GrantMessages.GRANTING) else failed(GrantMessages.CONNECT_FAILED)
                else -> null
            }
            GrantPhase.GRANTING -> when (event) {
                is GrantEvent.GrantResult ->
                    if (event.verified) Transition(GrantPhase.DONE, GrantMessages.DONE)
                    else failed(GrantMessages.GRANT_FAILED_PREFIX + event.detail.ifBlank { "원인 불명" })
                else -> null
            }
        }
    }

    private fun failed(message: String) = Transition(GrantPhase.FAILED, message)
}
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew --offline testDebugUnitTest assembleDebug 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL` (테스트 클래스 7개: 기존 4 + 새 3).

---

### Task 3: AdbPortDiscovery, AdbGrantNotifications, AdbCodeReceiver, AdbGrantService + 매니페스트 선언

Android API에 묶인 클래스라 JVM 테스트 없음. 게이트는 컴파일 + 기존 테스트 7클래스 통과.

**Files:**
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/adb/AdbPortDiscovery.kt`
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/adb/AdbGrantNotifications.kt`
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/adb/AdbCodeReceiver.kt`
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/adb/AdbGrantService.kt`
- Modify: `app/src/main/AndroidManifest.xml` (service·receiver 추가)

**Interfaces:**
- Consumes: Task 1·2 산출물, 기존 `PermissionState.canDeleteSms`, `MainActivity`, `R.drawable.ic_app`.
- Produces: `AdbPortDiscovery(context)` with `start()`, `stop()`, `pairingPort()`, `connectPort()`, `awaitPairingPort(ms)`, `awaitConnectPort(ms)`; `AdbGrantNotifications(context)` with `ensureChannel()`, `build(phase, message)`, `show(phase, message)`, consts `CHANNEL_ID`, `NOTIFICATION_ID = 4101`, `KEY_CODE`; `AdbCodeReceiver` (actions `ACTION_CODE`, `ACTION_CANCEL`); `AdbGrantService` (actions `ACTION_START/CODE/MANUAL_PORTS/CANCEL`, extras `EXTRA_CODE/EXTRA_PAIRING_PORT/EXTRA_CONNECT_PORT`, helpers `startIntent(context)`, `cancelIntent(context)`, `manualPortsIntent(context, pairing, connect)`, const `SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"`).

- [ ] **Step 1: AdbPortDiscovery.kt**

```kotlin
package com.interbb.disasterinboxcleaner.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.util.ArrayDeque

/**
 * Finds the phone's own wireless-debugging ports over mDNS, the way LADB does:
 * `_adb-tls-pairing._tcp` is advertised only while the Settings pairing dialog is open,
 * `_adb-tls-connect._tcp` whenever wireless debugging is on. The newest advertisement wins.
 */
class AdbPortDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val pairing = PortSelector()
    private val connect = PortSelector()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listeners.isNotEmpty()) return
        pairing.clear()
        connect.clear()
        multicastLock = wifi.createMulticastLock(LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        for (type in listOf(TYPE_PAIRING, TYPE_CONNECT)) {
            val listener = discoveryListener()
            listeners += listener
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { Log.w(TAG, "discoverServices($type) failed: ${it.message}") }
        }
    }

    @Synchronized
    fun stop() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        resolveQueue.clear()
        resolving = false
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    fun pairingPort(): Int? = pairing.best(System.currentTimeMillis())

    fun connectPort(): Int? = connect.best(System.currentTimeMillis())

    fun awaitPairingPort(timeoutMs: Long): Int? = await(timeoutMs) { pairingPort() }

    fun awaitConnectPort(timeoutMs: Long): Int? = await(timeoutMs) { connectPort() }

    private fun await(timeoutMs: Long, read: () -> Int?): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return read()
    }

    private fun discoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "start failed $serviceType: $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) = enqueueResolve(info)
        override fun onServiceLost(info: NsdServiceInfo) {}
    }

    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        if (!resolving) resolveNext()
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun resolveNext() {
        val info = resolveQueue.pollFirst()
        if (info == null) {
            resolving = false
            return
        }
        resolving = true
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = resolveNext()
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val advert = Advert(serviceInfo.serviceName, serviceInfo.port, System.currentTimeMillis())
                    if (serviceInfo.serviceType.contains("pairing")) pairing.offer(advert) else connect.offer(advert)
                    resolveNext()
                }
            },
        )
    }

    companion object {
        private const val TAG = "AdbPortDiscovery"
        private const val LOCK_TAG = "adb-port-discovery"
        private const val POLL_MS = 500L
        const val TYPE_PAIRING = "_adb-tls-pairing._tcp"
        const val TYPE_CONNECT = "_adb-tls-connect._tcp"
    }
}
```

- [ ] **Step 2: AdbGrantNotifications.kt**

```kotlin
package com.interbb.disasterinboxcleaner.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import com.interbb.disasterinboxcleaner.MainActivity
import com.interbb.disasterinboxcleaner.R

/** One fixed-id notification that carries the grant flow: code input, progress, done, failed. */
class AdbGrantNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "폰에서 권한 부여", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "메시지함 삭제 권한을 폰에서 받는 동안 코드 입력과 진행 상태를 보여줍니다."
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(phase: GrantPhase, message: String): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title(phase))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
        when (phase) {
            GrantPhase.WAITING_CODE -> {
                val remoteInput = RemoteInput.Builder(KEY_CODE).setLabel("6자리 코드").build()
                val reply = Notification.Action.Builder(null, "보내기", codeIntent())
                    .addRemoteInput(remoteInput)
                    .build()
                val cancel = Notification.Action.Builder(null, "취소", cancelIntent()).build()
                builder.addAction(reply).addAction(cancel).setOngoing(true)
            }
            GrantPhase.PAIRING, GrantPhase.CONNECTING, GrantPhase.GRANTING ->
                builder.setOngoing(true).setProgress(0, 0, true)
            GrantPhase.DONE, GrantPhase.FAILED, GrantPhase.IDLE ->
                builder.setOngoing(false).setAutoCancel(true)
        }
        return builder.build()
    }

    fun show(phase: GrantPhase, message: String) {
        if (phase == GrantPhase.IDLE) {
            manager.cancel(NOTIFICATION_ID)
        } else {
            manager.notify(NOTIFICATION_ID, build(phase, message))
        }
    }

    private fun title(phase: GrantPhase): String = when (phase) {
        GrantPhase.WAITING_CODE -> "페어링 코드 입력"
        GrantPhase.PAIRING, GrantPhase.CONNECTING, GrantPhase.GRANTING -> "권한 부여 진행 중"
        GrantPhase.DONE -> "메시지함 삭제 권한 완료"
        GrantPhase.FAILED -> "권한 부여 실패"
        GrantPhase.IDLE -> "문자함 정리"
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun codeIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, AdbCodeReceiver::class.java).setAction(AdbCodeReceiver.ACTION_CODE),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        2,
        Intent(context, AdbCodeReceiver::class.java).setAction(AdbCodeReceiver.ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "adb_grant"
        const val NOTIFICATION_ID = 4101
        const val KEY_CODE = "code"
    }
}
```

- [ ] **Step 3: AdbCodeReceiver.kt**

```kotlin
package com.interbb.disasterinboxcleaner.adb

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Receives the notification's inline reply (or cancel) and forwards it to the grant service. */
class AdbCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, AdbGrantService::class.java)
        when (intent.action) {
            ACTION_CODE -> {
                val typed = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(AdbGrantNotifications.KEY_CODE)
                    ?.toString()
                    .orEmpty()
                val code = typed.filter { it.isDigit() }
                if (code.length != 6) {
                    AdbGrantNotifications(context).show(
                        GrantPhase.WAITING_CODE,
                        "6자리 숫자만 입력하세요. " + GrantMessages.WAITING,
                    )
                    return
                }
                service.action = AdbGrantService.ACTION_CODE
                service.putExtra(AdbGrantService.EXTRA_CODE, code)
            }
            ACTION_CANCEL -> service.action = AdbGrantService.ACTION_CANCEL
            else -> return
        }
        ContextCompat.startForegroundService(context, service)
    }

    companion object {
        const val ACTION_CODE = "com.interbb.disasterinboxcleaner.adb.CODE_REPLY"
        const val ACTION_CANCEL = "com.interbb.disasterinboxcleaner.adb.CANCEL_REPLY"
    }
}
```

- [ ] **Step 4: AdbGrantService.kt**

```kotlin
package com.interbb.disasterinboxcleaner.adb

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.interbb.disasterinboxcleaner.PermissionState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that walks the on-phone grant flow: wait for the pairing code from the
 * notification, pair, connect, run `appops set <pkg> WRITE_SMS allow`, verify, stop.
 * Every transition is written to AdbGrantPreferences and mirrored in the notification.
 */
class AdbGrantService : Service() {
    private lateinit var prefs: AdbGrantPreferences
    private lateinit var notifications: AdbGrantNotifications
    private lateinit var adb: AdbBinary
    private lateinit var discovery: AdbPortDiscovery
    private lateinit var executor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var phase = GrantPhase.IDLE
    @Volatile private var manualPairingPort: Int? = null
    @Volatile private var manualConnectPort: Int? = null

    private val timeoutRunnable = Runnable {
        transition(GrantEvent.Timeout)
        finish()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AdbGrantPreferences(this)
        notifications = AdbGrantNotifications(this).also { it.ensureChannel() }
        adb = AdbBinary(this)
        discovery = AdbPortDiscovery(this)
        executor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_CODE -> intent.getStringExtra(EXTRA_CODE)?.let { code ->
                if (phase == GrantPhase.WAITING_CODE) executor.execute { runGrant(code) }
            }
            ACTION_MANUAL_PORTS -> {
                manualPairingPort = intent.getIntExtra(EXTRA_PAIRING_PORT, 0).takeIf { it > 0 }
                manualConnectPort = intent.getIntExtra(EXTRA_CONNECT_PORT, 0).takeIf { it > 0 }
            }
            ACTION_CANCEL -> {
                transition(GrantEvent.Cancel)
                finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val shownPhase = if (phase == GrantPhase.IDLE) GrantPhase.WAITING_CODE else phase
        val message = prefs.snapshot().message.ifBlank { GrantMessages.WAITING }
        val notification = notifications.build(shownPhase, message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AdbGrantNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(AdbGrantNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun start() {
        if (GrantStatus(phase).inProgress) return
        phase = GrantPhase.IDLE
        when {
            !adb.isAvailable() -> {
                transition(GrantEvent.BinaryMissing)
                finish()
                return
            }
            !wirelessDebuggingEnabled() -> {
                transition(GrantEvent.WirelessDebuggingOff)
                finish()
                return
            }
            !onWifi() -> {
                transition(GrantEvent.NoWifi)
                finish()
                return
            }
        }
        transition(GrantEvent.Start)
        discovery.start()
        mainHandler.postDelayed(timeoutRunnable, WAIT_TIMEOUT_MS)
    }

    private fun runGrant(code: String) {
        mainHandler.removeCallbacks(timeoutRunnable)
        transition(GrantEvent.CodeEntered)
        try {
            val pairingPort = manualPairingPort ?: discovery.awaitPairingPort(PAIRING_PORT_TIMEOUT_MS)
            if (pairingPort == null) {
                transition(GrantEvent.PairingPortMissing)
                return
            }
            if (!AdbOutcomes.pairSucceeded(adb.pair(pairingPort, code))) {
                transition(GrantEvent.PairResult(false))
                return
            }
            transition(GrantEvent.PairResult(true))

            val connectPort = manualConnectPort ?: discovery.awaitConnectPort(CONNECT_PORT_TIMEOUT_MS)
            if (connectPort == null) {
                transition(GrantEvent.ConnectPortMissing)
                return
            }
            if (!AdbOutcomes.connectSucceeded(adb.connect(connectPort))) {
                transition(GrantEvent.ConnectResult(false))
                return
            }
            transition(GrantEvent.ConnectResult(true))

            val grant = adb.shell("appops", "set", packageName, "WRITE_SMS", "allow")
            val verified = AdbOutcomes.grantSucceeded(grant) && PermissionState.canDeleteSms(this)
            val detail = AdbOutcomes.maskCode(grant.output)
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            transition(GrantEvent.GrantResult(verified, detail))
        } finally {
            finish()
        }
    }

    private fun transition(event: GrantEvent) {
        val next = AdbGrantMachine.next(phase, event) ?: return
        phase = next.phase
        prefs.set(next.phase, next.message)
        mainHandler.post { notifications.show(next.phase, next.message) }
    }

    private fun finish() {
        mainHandler.removeCallbacks(timeoutRunnable)
        discovery.stop()
        executor.execute {
            adb.killServer()
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        discovery.stop()
        executor.shutdown()
        super.onDestroy()
    }

    private fun wirelessDebuggingEnabled(): Boolean =
        Settings.Global.getInt(contentResolver, SETTING_ADB_WIFI_ENABLED, 0) == 1

    private fun onWifi(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        const val ACTION_START = "com.interbb.disasterinboxcleaner.adb.START"
        const val ACTION_CODE = "com.interbb.disasterinboxcleaner.adb.CODE"
        const val ACTION_MANUAL_PORTS = "com.interbb.disasterinboxcleaner.adb.MANUAL_PORTS"
        const val ACTION_CANCEL = "com.interbb.disasterinboxcleaner.adb.CANCEL"
        const val EXTRA_CODE = "code"
        const val EXTRA_PAIRING_PORT = "pairingPort"
        const val EXTRA_CONNECT_PORT = "connectPort"
        const val SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val WAIT_TIMEOUT_MS = 10 * 60 * 1000L
        private const val PAIRING_PORT_TIMEOUT_MS = 15_000L
        private const val CONNECT_PORT_TIMEOUT_MS = 20_000L

        fun startIntent(context: Context): Intent =
            Intent(context, AdbGrantService::class.java).setAction(ACTION_START)

        fun cancelIntent(context: Context): Intent =
            Intent(context, AdbGrantService::class.java).setAction(ACTION_CANCEL)

        fun manualPortsIntent(context: Context, pairingPort: Int?, connectPort: Int?): Intent =
            Intent(context, AdbGrantService::class.java)
                .setAction(ACTION_MANUAL_PORTS)
                .putExtra(EXTRA_PAIRING_PORT, pairingPort ?: 0)
                .putExtra(EXTRA_CONNECT_PORT, connectPort ?: 0)
    }
}
```

- [ ] **Step 5: AndroidManifest.xml에 서비스·리시버 추가**

`<receiver android:name=".BootCompletedReceiver" ...>` 블록 바로 앞에 추가:
```xml
        <service
            android:name=".adb.AdbGrantService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Pairs with the phone's own wireless debugging to grant this app the WRITE_SMS app-op once" />
        </service>

        <receiver
            android:name=".adb.AdbCodeReceiver"
            android:exported="false" />
```

- [ ] **Step 6: 컴파일·회귀 확인**

Run: `./gradlew --offline testDebugUnitTest assembleDebug 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`. 컴파일 오류가 나면 이름·구조를 유지한 채 최소 수정하고 보고서에 적는다.

---

### Task 4: 안내 이미지 캡처·주석 (폰 필요, 컨트롤러가 사용자와 실행)

Task 3까지 빌드된 APK를 폰에 `install -r`한 뒤 실행한다(이미지 7은 실제 코드 입력 알림을 찍어야 하므로). 결과물은 `app/src/main/res/drawable-nodpi/guide_01_software_info.webp` … `guide_08_toggle_off.webp` 8장과 도구 3개. 개인정보(IP·코드·계정·기기명)는 마스킹한다. 접은 화면(세로)에서 찍는다.

**Files:**
- Create: `tools/guide/capture_guide.sh`
- Create: `tools/guide/find_bounds.py`
- Create: `tools/guide/annotate_guide.py`
- Create: `app/src/main/res/drawable-nodpi/guide_0[1-8]_*.webp` (8장)

- [ ] **Step 1: find_bounds.py (uiautomator 덤프에서 글자로 노드 좌표 찾기)**

```python
#!/usr/bin/env python3
"""Print the bounds [l,t,r,b] of the first node whose text or content-desc contains the query."""
import re, sys, xml.etree.ElementTree as ET

def main():
    if len(sys.argv) < 3:
        print("usage: find_bounds.py DUMP.xml QUERY [QUERY...]", file=sys.stderr); sys.exit(2)
    root = ET.parse(sys.argv[1]).getroot()
    queries = sys.argv[2:]
    for node in root.iter("node"):
        hay = (node.get("text") or "") + "|" + (node.get("content-desc") or "")
        if any(q in hay for q in queries):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if m:
                print(" ".join(m.groups())); return
    print("NOTFOUND"); sys.exit(1)

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: capture_guide.sh (화면 열기 → 덤프 → 좌표 → 캡처)**

```bash
#!/bin/bash
# Captures the eight guide screenshots from the connected Galaxy (folded, portrait) and writes
# tools/guide/raw/<name>.png plus tools/guide/boxes.json (highlight boxes + masks, from uiautomator bounds).
# Requires: adb device, wireless debugging OFF at start (step 3-4 turns it on), app 1.2.0 installed.
set -u
HERE=$(cd "$(dirname "$0")" && pwd); RAW="$HERE/raw"; mkdir -p "$RAW"
PKG=com.interbb.disasterinboxcleaner
BOXES="$HERE/boxes.json"; echo "{}" > "$BOXES"
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$RAW/ui.xml" >/dev/null 2>&1; }
bounds() { python3 "$HERE/find_bounds.py" "$RAW/ui.xml" "$@"; }
center() { echo "$1" | awk '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'; }
shot() { adb exec-out screencap -p > "$RAW/$1.png"; echo "shot $1"; }
addbox() { python3 - "$BOXES" "$1" "$2" "$3" <<'EOF'
import json,sys
p,name,kind,b=sys.argv[1:5]; d=json.load(open(p)); e=d.setdefault(name,{"boxes":[],"masks":[]}); e[kind].append([int(x) for x in b.split()]); json.dump(d,open(p,'w'),indent=1)
EOF
}
find_scrolling() { # $1=name, rest=queries; scrolls down up to 6 times until found; prints bounds
  for i in 1 2 3 4 5 6; do dump; b=$(bounds "$@"); [ "$b" != "NOTFOUND" ] && { echo "$b"; return 0; }; adb shell input swipe 540 1700 540 700 300; adb shell sleep 1; done; echo NOTFOUND; return 1; }

echo "## 1 휴대전화 정보 → 소프트웨어 정보"
adb shell am start -a android.settings.DEVICE_INFO_SETTINGS >/dev/null; adb shell sleep 2
B=$(find_scrolling "소프트웨어 정보") && { shot guide_01_software_info; addbox guide_01_software_info boxes "$B"; adb shell input tap $(center "$B"); adb shell sleep 2; }
echo "## 2 빌드번호"
B=$(find_scrolling "빌드번호") && { shot guide_02_build_number; addbox guide_02_build_number boxes "$B"; }
echo "## 3 개발자 옵션 → 무선 디버깅 스위치"
adb shell am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS >/dev/null; adb shell sleep 2
B=$(find_scrolling "무선 디버깅") && { shot guide_03_dev_wireless_switch; addbox guide_03_dev_wireless_switch boxes "$B"; }
echo "## 4 허용 확인창 (스위치를 켜면 뜸)"
adb shell input tap $(echo "$B" | awk '{printf "%d %d", $3-60, ($2+$4)/2}'); adb shell sleep 2; dump
A=$(bounds "허용" "Allow"); [ "$A" != "NOTFOUND" ] && { shot guide_04_wireless_allow; addbox guide_04_wireless_allow boxes "$A"; adb shell input tap $(center "$A"); adb shell sleep 2; }
echo "## 5 무선 디버깅 화면 → 페어링 코드로 기기 페어링"
dump; B=$(bounds "무선 디버깅"); adb shell input tap $(echo "$B" | awk '{printf "%d %d", $1+200, ($2+$4)/2}'); adb shell sleep 2
P=$(find_scrolling "페어링 코드로 기기 페어링" "페어링 코드") && { shot guide_05_wireless_page; addbox guide_05_wireless_page boxes "$P"; adb shell input tap $(center "$P"); adb shell sleep 2; }
echo "## 6 페어링 대화상자 (코드 마스킹)"
dump; C=$(python3 - "$RAW/ui.xml" <<'EOF'
import re,sys,xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    t=n.get("text") or ""
    if re.fullmatch(r"\d{6}", t.strip()):
        m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds","")); print(" ".join(m.groups())); break
else: print("NOTFOUND")
EOF
); shot guide_06_pairing_dialog; [ "$C" != "NOTFOUND" ] && { addbox guide_06_pairing_dialog masks "$C"; addbox guide_06_pairing_dialog boxes "$C"; }
IPB=$(bounds "IP" "주소"); [ "$IPB" != "NOTFOUND" ] && addbox guide_06_pairing_dialog masks "$IPB"
adb shell input keyevent BACK; adb shell sleep 1
echo "## 7 코드 입력 알림 (서비스 시작 → 알림창 펼침)"
adb shell am start-foreground-service -n $PKG/.adb.AdbGrantService -a com.interbb.disasterinboxcleaner.adb.START >/dev/null 2>&1; adb shell sleep 3
adb shell cmd statusbar expand-notifications; adb shell sleep 2; dump
N=$(bounds "보내기" "페어링 코드 입력"); shot guide_07_notification_input; [ "$N" != "NOTFOUND" ] && addbox guide_07_notification_input boxes "$N"
adb shell cmd statusbar collapse; adb shell am start-foreground-service -n $PKG/.adb.AdbGrantService -a com.interbb.disasterinboxcleaner.adb.CANCEL >/dev/null 2>&1
echo "## 8 무선 디버깅 끄기 (개발자 옵션의 켜진 스위치)"
adb shell am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS >/dev/null; adb shell sleep 2
B=$(find_scrolling "무선 디버깅") && { shot guide_08_toggle_off; addbox guide_08_toggle_off boxes "$B"; }
echo "done: $(ls "$RAW"/*.png | wc -l) shots; boxes in $BOXES"
```
실행 중 글자가 안 잡히면(NOTFOUND) 해당 장면은 `uiautomator dump`를 보고 문구를 고쳐 그 단계만 다시 찍는다. 무선 디버깅은 4단계에서 켜지며 켜 둔 채 둔다(E2E에 필요).

- [ ] **Step 3: annotate_guide.py (빨간 박스·마스킹·WebP)**

```python
#!/usr/bin/env python3
"""Draw red highlight boxes and gray masks from boxes.json onto raw PNGs, downscale, save WebP."""
import json, os, sys
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable-nodpi")
MAX_LONG = 1080
BOX = (229, 57, 53)
NAMES = ["guide_01_software_info", "guide_02_build_number", "guide_03_dev_wireless_switch",
         "guide_04_wireless_allow", "guide_05_wireless_page", "guide_06_pairing_dialog",
         "guide_07_notification_input", "guide_08_toggle_off"]

def main():
    boxes = json.load(open(os.path.join(HERE, "boxes.json")))
    os.makedirs(OUT, exist_ok=True)
    for name in NAMES:
        src = os.path.join(RAW, name + ".png")
        if not os.path.exists(src):
            print("missing", name); continue
        im = Image.open(src).convert("RGB")
        draw = ImageDraw.Draw(im)
        entry = boxes.get(name, {})
        for l, t, r, b in entry.get("masks", []):
            draw.rectangle([l, t, r, b], fill=(200, 200, 200))
        for l, t, r, b in entry.get("boxes", []):
            pad = 12
            draw.rounded_rectangle([l - pad, t - pad, r + pad, b + pad], radius=12, outline=BOX, width=6)
        scale = min(1.0, MAX_LONG / max(im.size))
        if scale < 1.0:
            im = im.resize((round(im.width * scale), round(im.height * scale)), Image.LANCZOS)
        dst = os.path.join(OUT, name + ".webp")
        im.save(dst, "WEBP", quality=80, method=6)
        print(name, im.size, os.path.getsize(dst) // 1024, "KB")

if __name__ == "__main__":
    main()
```

- [ ] **Step 4: 실행과 확인**

```bash
cd /Users/interbb/workspace/private/intellij/DisasterInboxCleaner && adb install -r app/build/outputs/apk/debug/app-debug.apk && /bin/bash tools/guide/capture_guide.sh && python3 tools/guide/annotate_guide.py && ls -la app/src/main/res/drawable-nodpi/
```
Expected: WebP 8장, 각 200KB 이하. `guide_06`의 코드·IP 영역이 회색으로 가려졌는지 눈으로 확인(Read로 열어 본다). 부족하면 `boxes.json`을 손보고 `annotate_guide.py`만 재실행.

---

### Task 5: UI — ViewModel 상태, AdbSelfGrantCard, 온보딩·설정 교체, GuideSteps/GuideScreen, MainActivity

Task 4의 WebP 8장이 있어야 컴파일된다. Compose UI라 JVM 테스트 없음. 게이트: 컴파일 + 기존 테스트 7클래스.

**Files:**
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/MainViewModel.kt`
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/ui/UiComponents.kt`
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/ui/OnboardingScreen.kt`
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/ui/SettingsScreen.kt`
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/ui/GuideSteps.kt`
- Create: `app/src/main/java/com/interbb/disasterinboxcleaner/ui/GuideScreen.kt`
- Modify: `app/src/main/java/com/interbb/disasterinboxcleaner/MainActivity.kt` (전체 교체)

**Interfaces:**
- Consumes: `AdbGrantPreferences`, `GrantStatus`, `GrantPhase`, `AdbGrantService.startIntent/cancelIntent/manualPortsIntent`(Task 2·3), 8개 drawable(Task 4), 기존 `PermissionSetupCard`·`StatusRow`·`formatTime`.
- Produces: `MonitorUiState.adbGrant: GrantStatus`; `AdbSelfGrantCard(...)`; `OnboardingScreen`/`SettingsScreen`에 `onOpenGuide`, `onStartAdbGrant` 매개변수; `GuideTarget`, `GuideAction`, `GuideStep`, `GuideSteps.steps`; `GuideScreen(...)`; `AppScreen.GUIDE`.

- [ ] **Step 1: MainViewModel.kt (여섯 군데 삽입)**

1. import 블록에 추가:
```kotlin
import com.interbb.disasterinboxcleaner.adb.AdbGrantPreferences
import com.interbb.disasterinboxcleaner.adb.GrantStatus
```
2. `MonitorUiState`의 `val ad: AdUiState = AdUiState(),` 다음 줄:
```kotlin
    val adbGrant: GrantStatus = GrantStatus(),
```
3. `private val adPreferences = AdMonitorPreferences(application)` 다음 줄:
```kotlin
    private val adbGrantPreferences = AdbGrantPreferences(application)
```
4. `init`의 `adPreferences.registerListener(preferenceListener)` 다음 줄:
```kotlin
        adbGrantPreferences.registerListener(preferenceListener)
```
5. `refresh()`에서 `ad = AdUiState( … ),` 블록이 끝난 다음 줄(닫는 괄호 `)` 앞):
```kotlin
            adbGrant = adbGrantPreferences.snapshot(),
```
6. `onCleared()`의 `adPreferences.unregisterListener(preferenceListener)` 다음 줄:
```kotlin
        adbGrantPreferences.unregisterListener(preferenceListener)
```

- [ ] **Step 2: UiComponents.kt에 AdbSelfGrantCard 추가**

import 블록에 추가(없는 것만):
```kotlin
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.interbb.disasterinboxcleaner.adb.GrantPhase
import com.interbb.disasterinboxcleaner.adb.GrantStatus
```
파일 끝에 추가:
```kotlin
/** Onboarding/settings card for the on-phone WRITE_SMS grant, with the PC adb command folded away. */
@Composable
internal fun AdbSelfGrantCard(
    title: String,
    granted: Boolean,
    status: GrantStatus,
    onOpenGuide: () -> Unit,
    onStart: () -> Unit,
    adbCommand: String,
    onCopyAdbCommand: () -> Unit,
) {
    var showFallback by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (granted) "승인됨" else "필요",
                    color = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "PC 없이 폰에서 한 번만 받으면 됩니다. 처음이면 '안내 보기'를 눌러 화면대로 따라 하세요.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status.phase != GrantPhase.IDLE) {
                Text(
                    status.message,
                    color = if (status.phase == GrantPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!granted) {
                Button(onClick = onOpenGuide, modifier = Modifier.fillMaxWidth()) {
                    Text("안내 보기 (처음이면 여기)")
                }
                OutlinedButton(
                    onClick = onStart,
                    enabled = !status.inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (status.inProgress) "진행 중…" else "바로 시작 (해 본 적 있으면)")
                }
            }
            TextButton(onClick = { showFallback = !showFallback }) {
                Text(if (showFallback) "다른 방법 접기" else "다른 방법 (PC에서 adb)")
            }
            if (showFallback) {
                Text("PC에 USB로 연결하고 다음 명령을 한 번 실행하세요.", style = MaterialTheme.typography.bodySmall)
                Text(adbCommand, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onCopyAdbCommand, modifier = Modifier.fillMaxWidth()) {
                    Text("명령 복사")
                }
            }
        }
    }
}
```

- [ ] **Step 3: OnboardingScreen.kt**

시그니처에 매개변수 추가(`onCopyAdbCommand: () -> Unit,` 다음 줄):
```kotlin
    onOpenGuide: () -> Unit,
    onStartAdbGrant: () -> Unit,
```
`AdbPermissionSetupCard(` 로 시작하는 `item { … }` 블록 전체를 다음으로 교체:
```kotlin
            item {
                AdbSelfGrantCard(
                    title = "3. 메시지함 삭제 권한",
                    granted = state.smsDeleteGranted,
                    status = state.adbGrant,
                    onOpenGuide = onOpenGuide,
                    onStart = onStartAdbGrant,
                    adbCommand = adbCommand,
                    onCopyAdbCommand = onCopyAdbCommand,
                )
            }
```

- [ ] **Step 4: SettingsScreen.kt**

시그니처에 매개변수 추가(`onRequestBatteryExemption: () -> Unit,` 다음 줄):
```kotlin
    onOpenGuide: () -> Unit,
    onStartAdbGrant: () -> Unit,
```
`AdbPermissionSetupCard(` 로 시작하는 `item { … }` 블록 전체를 다음으로 교체:
```kotlin
            item {
                AdbSelfGrantCard(
                    title = "메시지함 삭제 권한",
                    granted = state.smsDeleteGranted,
                    status = state.adbGrant,
                    onOpenGuide = onOpenGuide,
                    onStart = onStartAdbGrant,
                    adbCommand = adbCommand,
                    onCopyAdbCommand = onCopyAdbCommand,
                )
            }
```
"앱 정보" 카드에 `Text("인터넷 권한 없음")`을 다음으로 교체:
```kotlin
                        Text("인터넷 접속 없음 (내장 adb는 이 폰 안에서만 통신)")
                        Text("동봉 adb: Apache License 2.0 (AOSP), LADB 빌드")
```

- [ ] **Step 5: GuideSteps.kt**

```kotlin
package com.interbb.disasterinboxcleaner.ui

import android.content.Context
import android.provider.Settings
import androidx.annotation.DrawableRes
import com.interbb.disasterinboxcleaner.R
import com.interbb.disasterinboxcleaner.adb.AdbGrantService

enum class GuideTarget {
    DEVICE_INFO,
    DEVELOPER_OPTIONS,
    WIRELESS_DEBUGGING,
}

sealed interface GuideAction {
    data class OpenSettings(val target: GuideTarget, val label: String) : GuideAction
    data object StartGrant : GuideAction
    data object Next : GuideAction
}

data class GuideStep(
    val title: String,
    val instruction: String,
    @DrawableRes val images: List<Int>,
    val action: GuideAction,
    val check: ((Context) -> Boolean)? = null,
)

/** The novice guide: one action per screen, real One UI screenshots, wording quotes on-screen labels. */
object GuideSteps {
    fun developerOptionsOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun wirelessDebuggingOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, AdbGrantService.SETTING_ADB_WIFI_ENABLED, 0) == 1

    val steps: List<GuideStep> = listOf(
        GuideStep(
            title = "시작하기 전에",
            instruction = "광고 문자를 지우려면 폰의 숨은 설정 하나가 필요합니다. 5단계, 약 3분, PC 없이 됩니다. 폰이 Wi-Fi에 연결돼 있어야 합니다.",
            images = emptyList(),
            action = GuideAction.Next,
        ),
        GuideStep(
            title = "1. 개발자 옵션 켜기",
            instruction = "설정 → '휴대전화 정보' → '소프트웨어 정보' → '빌드번호'를 7번 연속 톡톡 누르세요. '개발자 모드를 켰습니다'가 뜨면 성공입니다.",
            images = listOf(R.drawable.guide_01_software_info, R.drawable.guide_02_build_number),
            action = GuideAction.OpenSettings(GuideTarget.DEVICE_INFO, "휴대전화 정보 열기"),
            check = ::developerOptionsOn,
        ),
        GuideStep(
            title = "2. 무선 디버깅 켜기",
            instruction = "설정 → '개발자 옵션' → '무선 디버깅' 스위치를 켜고, 확인창에서 '허용'을 누르세요.",
            images = listOf(R.drawable.guide_03_dev_wireless_switch, R.drawable.guide_04_wireless_allow),
            action = GuideAction.OpenSettings(GuideTarget.DEVELOPER_OPTIONS, "개발자 옵션 열기"),
            check = ::wirelessDebuggingOn,
        ),
        GuideStep(
            title = "3. 페어링 창 열기",
            instruction = "'무선 디버깅' 글자를 눌러 들어가 '페어링 코드로 기기 페어링'을 누르세요. 6자리 숫자가 뜹니다. 이 창을 닫지 마세요. 아래 '권한 부여 시작'을 누르면 코드 입력 알림이 뜹니다.",
            images = listOf(R.drawable.guide_05_wireless_page, R.drawable.guide_06_pairing_dialog),
            action = GuideAction.StartGrant,
        ),
        GuideStep(
            title = "4. 코드 입력",
            instruction = "화면 맨 위를 아래로 쓸어내려 알림창을 열고, '문자함 정리' 알림의 칸에 6자리를 넣고 '보내기'를 누르세요. 잠시 뒤 결과가 뜹니다.",
            images = listOf(R.drawable.guide_07_notification_input),
            action = GuideAction.Next,
        ),
        GuideStep(
            title = "5. 완료",
            instruction = "완료되면 이제 무선 디버깅을 꺼도 됩니다. 설정 → '개발자 옵션' → '무선 디버깅' 끄기.",
            images = listOf(R.drawable.guide_08_toggle_off),
            action = GuideAction.OpenSettings(GuideTarget.DEVELOPER_OPTIONS, "개발자 옵션 열기"),
        ),
    )
}
```

- [ ] **Step 6: GuideScreen.kt**

```kotlin
package com.interbb.disasterinboxcleaner.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.interbb.disasterinboxcleaner.MonitorUiState
import com.interbb.disasterinboxcleaner.adb.GrantPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    state: MonitorUiState,
    onBack: () -> Unit,
    onOpenTarget: (GuideTarget) -> Unit,
    onStartGrant: () -> Unit,
    onCancelGrant: () -> Unit,
    onSubmitManualPorts: (Int?, Int?) -> Unit,
    adbCommand: String,
    onCopyAdbCommand: () -> Unit,
) {
    val context = LocalContext.current
    val steps = GuideSteps.steps
    var index by rememberSaveable { mutableIntStateOf(0) }
    var zoomed by remember { mutableStateOf<Int?>(null) }
    var showManualPorts by remember { mutableStateOf(false) }
    var showFallback by remember { mutableStateOf(false) }
    var pairingPortText by rememberSaveable { mutableStateOf("") }
    var connectPortText by rememberSaveable { mutableStateOf("") }
    val step = steps[index]
    val status = state.adbGrant
    val checked = step.check?.invoke(context) == true
    val isLast = index == steps.lastIndex

    LaunchedEffect(status.phase) {
        if (index == 3 && status.inProgress) index = 4
        if (index == 4 && status.phase == GrantPhase.DONE) index = 5
    }
    LaunchedEffect(state.smsDeleteGranted) {
        if (state.smsDeleteGranted && index in 3..4) index = 5
    }

    zoomed?.let { res ->
        Dialog(onDismissRequest = { zoomed = null }) {
            Image(
                painter = painterResource(res),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { zoomed = null },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("권한 설정 안내") },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
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
                Text(
                    "${index + 1} / ${steps.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { (index + 1f) / steps.size },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Text(step.title, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            item { Text(step.instruction, fontSize = 18.sp, lineHeight = 26.sp) }
            if (checked) {
                item {
                    Text(
                        "확인됨 ✓ 이 단계는 이미 되어 있습니다. '다음'을 누르세요.",
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            items(step.images) { res ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { zoomed = res },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Image(
                        painter = painterResource(res),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (index >= 3 && status.phase != GrantPhase.IDLE) {
                item {
                    Text(
                        status.message,
                        color = if (status.phase == GrantPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (val action = step.action) {
                        is GuideAction.OpenSettings -> Button(
                            onClick = { onOpenTarget(action.target) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(action.label) }
                        GuideAction.StartGrant -> Button(
                            onClick = onStartGrant,
                            enabled = !status.inProgress,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (status.inProgress) "진행 중…" else "권한 부여 시작") }
                        GuideAction.Next -> Button(
                            onClick = { if (!isLast) index++ else onBack() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (isLast) "끝" else "다음") }
                    }
                    if (status.phase == GrantPhase.FAILED && index in 3..4) {
                        Button(
                            onClick = {
                                index = 3
                                onStartGrant()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("다시 시도") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { index-- },
                            enabled = index > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("이전") }
                        if (step.action !is GuideAction.Next) {
                            OutlinedButton(
                                onClick = { if (!isLast) index++ else onBack() },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (isLast) "끝" else "다음") }
                        }
                    }
                    if (status.inProgress) {
                        TextButton(onClick = onCancelGrant) { Text("진행 취소") }
                    }
                }
            }
            if (index in 3..4) {
                item {
                    TextButton(onClick = { showManualPorts = !showManualPorts }) {
                        Text(if (showManualPorts) "포트 직접 입력 접기" else "포트 직접 입력 (안 될 때만)")
                    }
                    if (showManualPorts) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "페어링 창의 'IP주소:포트'에서 포트 숫자와, 무선 디버깅 화면 위쪽 'IP 주소 및 포트'의 포트 숫자를 적으세요.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = pairingPortText,
                                onValueChange = { pairingPortText = it.filter { c -> c.isDigit() }.take(5) },
                                label = { Text("페어링 포트") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = connectPortText,
                                onValueChange = { connectPortText = it.filter { c -> c.isDigit() }.take(5) },
                                label = { Text("연결 포트") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                onClick = { onSubmitManualPorts(pairingPortText.toIntOrNull(), connectPortText.toIntOrNull()) },
                                enabled = pairingPortText.isNotBlank() || connectPortText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("포트 적용") }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { showFallback = !showFallback }) {
                    Text(if (showFallback) "다른 방법 접기" else "다른 방법 (PC에서 adb)")
                }
                if (showFallback) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("PC에 USB로 연결하고 다음 명령을 한 번 실행하세요.", style = MaterialTheme.typography.bodySmall)
                        Text(adbCommand, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onCopyAdbCommand, modifier = Modifier.fillMaxWidth()) {
                            Text("명령 복사")
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 7: MainActivity.kt 전체 교체**

```kotlin
package com.interbb.disasterinboxcleaner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.interbb.disasterinboxcleaner.adb.AdbGrantService
import com.interbb.disasterinboxcleaner.ui.AdLogScreen
import com.interbb.disasterinboxcleaner.ui.GuideScreen
import com.interbb.disasterinboxcleaner.ui.GuideTarget
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
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        startGrantService()
                    } else {
                        Toast.makeText(this@MainActivity, "알림을 허용해야 코드를 입력할 수 있습니다", Toast.LENGTH_LONG).show()
                    }
                }

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
                val requestBatteryExemption = {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
                val startAdbGrant = {
                    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startGrantService()
                    }
                }
                val cancelAdbGrant = {
                    ContextCompat.startForegroundService(this@MainActivity, AdbGrantService.cancelIntent(this@MainActivity))
                }
                val submitManualPorts: (Int?, Int?) -> Unit = { pairing, connect ->
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        AdbGrantService.manualPortsIntent(this@MainActivity, pairing, connect),
                    )
                }
                val openGuide = { screenName = AppScreen.GUIDE.name }
                val goHome = {
                    if (screen == AppScreen.HISTORY) viewModel.clearHistoryFromMemory()
                    screenName = if (state.onboardingComplete) AppScreen.HOME.name else AppScreen.ONBOARDING.name
                }

                BackHandler(enabled = screen != AppScreen.HOME && screen != AppScreen.ONBOARDING) {
                    goHome()
                }

                when (screen) {
                    AppScreen.ONBOARDING -> OnboardingScreen(
                        state = state,
                        onRequestSmsPermission = requestSmsPermission,
                        onOpenNotificationAccess = openNotificationAccess,
                        onRequestBatteryExemption = requestBatteryExemption,
                        adbCommand = adbCommand,
                        onCopyAdbCommand = ::copyAdbCommand,
                        onOpenGuide = openGuide,
                        onStartAdbGrant = startAdbGrant,
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
                    AppScreen.GUIDE -> GuideScreen(
                        state = state,
                        onBack = goHome,
                        onOpenTarget = ::openGuideTarget,
                        onStartGrant = startAdbGrant,
                        onCancelGrant = cancelAdbGrant,
                        onSubmitManualPorts = submitManualPorts,
                        adbCommand = adbCommand,
                        onCopyAdbCommand = ::copyAdbCommand,
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
                        onRequestBatteryExemption = requestBatteryExemption,
                        onOpenGuide = openGuide,
                        onStartAdbGrant = startAdbGrant,
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

    private fun startGrantService() {
        ContextCompat.startForegroundService(this, AdbGrantService.startIntent(this))
    }

    /** Opens the Settings page a guide step points at; falls back to Developer options if a component is missing. */
    private fun openGuideTarget(target: GuideTarget) {
        val intent = when (target) {
            GuideTarget.DEVICE_INFO -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            GuideTarget.DEVELOPER_OPTIONS -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            GuideTarget.WIRELESS_DEBUGGING -> Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.Settings\$WirelessDebuggingActivity"),
            )
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        val resolvable = intent.resolveActivity(packageManager) != null
        runCatching { startActivity(if (resolvable) intent else fallback) }
            .onFailure { runCatching { startActivity(fallback) } }
    }

    private enum class AppScreen {
        ONBOARDING,
        HOME,
        HISTORY,
        AD_LOG,
        GUIDE,
        SETTINGS,
    }
}
```

- [ ] **Step 8: 컴파일·회귀 확인**

Run: `./gradlew --offline testDebugUnitTest assembleDebug 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`. 컴파일 오류(예: import 누락)는 이름·구조를 유지한 채 최소 수정하고 보고서에 적는다.

---

### Task 6: README, 최종 빌드, 삭제 후 재설치, 실기기 검증

**Files:**
- Modify: `README.md`

- [ ] **Step 1: README "## 설치와 최초 설정" 절 전체 교체**

기존 `## 설치와 최초 설정`부터 `## 백그라운드 동작` 직전까지를 다음으로 교체:
````markdown
## 설치와 최초 설정

이 앱은 Google Play 배포용이 아니라 개인용 사이드로드 앱입니다. 설치는 adb로 합니다(문자 권한이 "제한된 권한"이라 파일 관리자 설치본은 `제한된 설정 허용` 절차가 더 필요할 수 있습니다).

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

처음 설정 화면이 세 가지를 안내합니다.

1. **알림 접근**: `시스템 알림 접근 화면 열기` → **문자함 감시** 허용. 처음 허용할 때 저장되는 "허용 알림 유형"(대화·알림·무음)이 이후 바뀌지 않으므로, 1.1.0 이하에서 이미 허용했다면 앱을 삭제하고 다시 설치한 뒤 새로 허용합니다.
2. **문자 읽기**: `문자 권한 요청` 버튼으로 앱 안에서 요청합니다.
3. **메시지함 삭제 권한(WRITE_SMS 앱옵)**: Android에는 이 권한을 켜는 설정 화면이 없어 셸 권한이 필요합니다. 두 가지 방법이 있습니다.
   - **폰에서 권한 부여(권장, PC 불필요)**: `안내 보기`를 눌러 5단계를 따라 합니다. 개발자 옵션 → 무선 디버깅 켜기 → `페어링 코드로 기기 페어링` 창 열기 → 알림에 6자리 코드 입력. 앱이 동봉한 adb로 폰 자신과 페어링해 `appops set … WRITE_SMS allow`를 실행하고 결과를 검증합니다. 포트는 앱이 mDNS로 찾고, 못 찾으면 안내 화면의 `포트 직접 입력`을 씁니다. 한 번 받으면 재부팅·업데이트에도 유지되고, 끝나면 무선 디버깅을 꺼도 됩니다.
   - **PC에서 adb(대안)**: `adb shell appops set com.interbb.disasterinboxcleaner WRITE_SMS allow`. 카드의 `다른 방법`에서 명령을 복사할 수 있습니다.

권장 설정 하나: `배터리 최적화 제외 요청`을 승인하고, 삼성 기기는 설정 > 배터리 > 백그라운드 사용 제한 > 절전 예외 앱에 추가합니다. 백그라운드에서 앱이 얼면 정리가 미뤄집니다.

권한을 되돌리려면:

```bash
adb shell appops set com.interbb.disasterinboxcleaner WRITE_SMS default
adb shell pm revoke com.interbb.disasterinboxcleaner android.permission.READ_SMS
```

### 동봉 adb와 페어링 키

- `app/src/main/jniLibs/arm64-v8a/libadb.so`는 AOSP adb의 Android arm64 빌드(Apache License 2.0)로, LADB 저장소에서 가져왔습니다(`app/src/main/assets/licenses/`). arm64 기기 전용이며 앱은 이 바이너리를 폰 안(127.0.0.1)에서만 씁니다. 서버 포트는 5038입니다.
- 페어링 키는 앱 내부 `files/adbhome/.android/`에만 있습니다. 설정 > 개발자 옵션 > 무선 디버깅 > 페어링된 기기 목록에서 이 앱 항목을 지우면 다시는 접속할 수 없습니다.
- 페어링 코드와 포트는 로그·파일에 남기지 않습니다.

````

- [ ] **Step 2: README 마지막 "## 한계" 목록에 항목 추가**

```markdown
- 폰에서 권한 부여는 Android 11 이상, arm64, Wi-Fi 연결이 필요하고, 개발자 옵션·무선 디버깅·페어링 창 열기와 코드 입력은 사용자가 직접 합니다. 안내 이미지는 Galaxy Fold8(One UI 9, 접은 화면) 기준입니다.
```

- [ ] **Step 3: 최종 빌드·테스트**

```bash
./gradlew --offline testDebugUnitTest assembleDebug 2>&1 | grep -E "^e: |FAILED|BUILD" | head
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
for f in sorted(glob.glob('app/build/test-results/testDebugUnitTest/*.xml')):
    r=ET.parse(f).getroot(); print(r.get('name'), 'tests', r.get('tests'), 'failures', r.get('failures'), 'errors', r.get('errors'))
EOF
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "libadb.so|guide_0[1-8]" | wc -l
```
Expected: `BUILD SUCCESSFUL`; 테스트 클래스 7개, failures 0, errors 0; APK 안에 `libadb.so` 1 + WebP 8 = 9줄.

- [ ] **Step 4: 삭제 후 재설치 (컨트롤러, 사용자 지시)**

```bash
adb uninstall com.interbb.disasterinboxcleaner
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell appops get com.interbb.disasterinboxcleaner WRITE_SMS      # "No operations." 기대(초기화 확인)
adb shell dumpsys package com.interbb.disasterinboxcleaner | grep -E "versionName"
```

- [ ] **Step 5: 실기기 검증 (사용자 조작 + adb 확인)**

1. 앱 열기 → 처음 설정: 알림 접근 허용, 문자 권한 요청.
2. 3단계 카드 `안내 보기` → 안내 화면 0~2단계(개발자 옵션은 이미 켜져 있어 "확인됨", 무선 디버깅은 사용자가 켬) → 3단계 `권한 부여 시작` → 알림 권한 허용 → 설정에서 페어링 창 열기 → 4단계대로 알림에 코드 입력.
3. 확인: 알림 "메시지함 삭제 권한 완료", 안내 5단계로 자동 이동, 온보딩 3단계 "승인됨". adb:
   ```bash
   adb shell appops get com.interbb.disasterinboxcleaner WRITE_SMS    # WRITE_SMS: allow
   adb shell run-as com.interbb.disasterinboxcleaner ls files/adbhome/.android   # adbkey, adbkey.pub
   adb shell dumpsys activity services com.interbb.disasterinboxcleaner | grep -c AdbGrantService   # 0 (서비스 종료)
   ```
4. 실패 경로 1종: 무선 디버깅을 끈 채 `바로 시작` → 카드에 "무선 디버깅이 꺼져 있습니다. 2단계를 확인하세요."
5. `설정 완료` → 홈 스위치 두 개 켜기 → `기존 광고 문자 정리` 화면이 열리는지(회귀).
6. 사용자가 무선 디버깅 끄기 → `settings get global adb_wifi_enabled` = 0.

---

## 스펙 대비 점검 (작성자 자체 검토)

- §2 목표 → Task 3(서비스·알림·mDNS·부여), Task 4·5(안내 화면·이미지), Task 5(카드 교체·폴백), Task 6(검증).
- §4.1 새 파일 → Task 1(AdbBinary·AdbOutcomes), Task 2(PortSelector·AdbGrantState·AdbGrantMachine), Task 3(Discovery·Notifications·Receiver·Service), Task 5(GuideSteps·GuideScreen), Task 4(리소스). `AdbOutcomes.failureMessage`는 `GrantMessages`로 통합(스펙 §4.1 표기보다 단순화).
- §4.2 수정 파일 → Task 1(gradle·manifest), Task 3(manifest), Task 5(ViewModel·MainActivity·Onboarding·Settings·UiComponents), Task 6(README).
- §5 흐름 → Task 3 서비스 + Task 5 액티비티. §6 안내 → Task 4·5. §7 권한 → Task 1·3·5. §8 오류 → Task 2 메시지 + Task 3 분기. §9 테스트 → Task 1·2 JVM, Task 6 E2E. §10 한계 → README(Task 6).
- 타입 일치: `GrantStatus.inProgress`(Task 2)를 Task 3·5가 사용; `AdbGrantService.SETTING_ADB_WIFI_ENABLED`를 Task 5 `GuideSteps`가 사용; `AdbCodeReceiver.ACTION_CODE/ACTION_CANCEL`을 Task 3 알림이 사용; `AdbSelfGrantCard` 시그니처는 Task 5의 온보딩·설정 호출과 동일; `MainActivity`가 `SettingsScreen`에 넘기는 인자 순서는 Step 4의 시그니처(…, `onRequestBatteryExemption`, `onOpenGuide`, `onStartAdbGrant`, `onRefresh`)와 이름 지정 인자로 맞는다.
