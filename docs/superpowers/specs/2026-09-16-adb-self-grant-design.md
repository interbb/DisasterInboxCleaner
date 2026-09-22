# 내장 ADB로 메시지함 삭제 권한 자가 부여 설계 ("문자함 정리" v1.2.0)

- 작성일: 2026-09-16
- 대상: `workspace/private/intellij/DisasterInboxCleaner` (개인용 사이드로드, git 없음)
- 기준 기기: Galaxy Fold8 SM-F971N, Android 17 / One UI 9.0, arm64
- 상태: 설계 3개 절 승인. 사용자 지시(2026-09-16): 이후 스펙·계획·구현·검증을 승인 없이 끝까지 진행하고, 설치가 필요하면 앱 삭제(권한·appops 초기화) 후 재설치로 검증한다.

## 1. 배경과 확정된 사실

- 비기본 문자 앱이 문자함 행을 지우려면 `WRITE_SMS` 앱옵이 필요하고, 이를 줄 수 있는 것은 셸(uid 2000)뿐이다. 앱옵은 한 번 주면 재부팅·업데이트에도 유지되고, 앱 삭제나 기본 문자 앱 역할 변동 때만 초기화된다.
- 스파이크(2026-09-16, throwaway AdbProbe): LADB 저장소의 Apache-2.0 arm64 `libadb.so`(libc/libdl/libm만 링크)를 APK jniLibs에 넣고 `applicationInfo.nativeLibraryDir`에서 실행하면, 무선 디버깅이 켜진 이 폰에서 앱 안에서 `pair 127.0.0.1:<페어링포트> <코드>` → "Successfully paired", `connect 127.0.0.1:<연결포트>` 성공(이후 코드 없이 재접속), 셸은 uid 2000, `appops set <pkg> WRITE_SMS allow` 성공(호스트 adb로 교차 확인). 페어링 키는 `HOME` 아래 `.android`에 저장된다.
- LADB는 `NsdManager`로 `_adb-tls-connect._tcp`를 탐색해 연결 포트를 얻는다(최신 광고 우선). 페어링 서비스 `_adb-tls-pairing._tcp`는 페어링 대화상자가 열린 동안만 광고된다. 스파이크의 8초 고정 탐색은 아무것도 못 찾았으나(원인 미확인) LADB는 계속 탐색으로 성공한다.
- `getprop service.adb.tls.port`는 이 폰에서 비어 있다. `am broadcast --es`로 공백 있는 명령을 넘기면 기기 셸이 분리하므로, 앱 내부에서는 인자를 리스트로 넘긴다.
- 사용자 요구: 개입 최소화, 컴맹도 따라 할 수 있는 안내, 어려운 단계는 캡처 이미지로 설명.

## 2. 목표와 비목표

목표:
- PC 없이 폰 혼자서 `WRITE_SMS` 앱옵을 부여한다. 사용자는 개발자 옵션·무선 디버깅을 켜고 페어링 대화상자를 연 뒤, 알림 입력칸에 6자리 코드만 넣는다. 포트는 앱이 mDNS로 찾는다.
- 컴맹 기준의 단계별 안내 화면(한 화면 한 동작, 실제 One UI 9 캡처에 빨간 박스, 설정 화면 바로 열기 버튼).
- 온보딩 3단계와 설정 화면의 "메시지함 삭제" 카드를 이 흐름으로 교체하고 PC adb 명령은 접힌 폴백으로 남긴다.
- 실패 시 원인별 한 줄 안내와 재시도.

비목표:
- 무선 디버깅 자동 켜기/끄기(안내만, 사용자 결정). 재부팅 후 자동 재페어링(한 번만 필요). arm64 외 ABI. Android 11 미만. Shizuku 연동. 임의 셸 명령 실행 UI.

## 3. 결정 기록

| 항목 | 결정 |
|---|---|
| 코드 입력 | 알림 인라인 입력(RemoteInput). 설정 화면을 벗어나지 않아도 됨 |
| 성공 후 무선 디버깅 | 안내만("꺼도 됩니다"). 앱이 끄지 않음 |
| adb 바이너리 | LADB 저장소 `app/src/main/jniLibs/arm64-v8a/libadb.so`(Apache-2.0, AOSP adb) 동봉, 앱 정보에 고지 |
| 배치 | 온보딩 3단계 카드 교체 + 설정 화면 동일, PC adb는 접힌 "다른 방법" |
| 접근 | A. 포그라운드 서비스 오케스트레이터(specialUse) |
| 안내 | 컴맹용 5단계 안내 화면, 어려운 단계는 캡처 이미지(접은 화면 기준) |
| minSdk | 30(무선 디버깅은 Android 11+) |
| 검증 | 앱 삭제 → 재설치 → 안내대로 부여 → 호스트 adb `appops get`으로 교차 확인 |
| adb 서버 포트 | 5037 대신 5038(`ANDROID_ADB_SERVER_PORT`)로 Shizuku 등과 충돌 회피 |
| 버전 | versionCode 8, versionName "1.2.0" |

## 4. 구조와 컴포넌트

새 파일(코드 9, 테스트 3, 리소스 8+1), 수정 7. 광고·재난문자 경로는 변경하지 않는다.

### 4.1 새 파일

**`adb/AdbBinary.kt`** — 동봉 바이너리 실행 래퍼.
```kotlin
data class AdbResult(val exitCode: Int, val output: String)

class AdbBinary(private val context: Context) {
    val file: File get() = File(context.applicationInfo.nativeLibraryDir, "libadb.so")
    fun isAvailable(): Boolean = file.exists() && file.canExecute()
    fun run(args: List<String>, timeoutSeconds: Long = 30): AdbResult   // ProcessBuilder(file + args), stderr 병합, env HOME=<filesDir>/adbhome, TMPDIR=<cacheDir>/adbtmp, ANDROID_ADB_SERVER_PORT=5038
    fun pair(port: Int, code: String): AdbResult = run(listOf("pair", "127.0.0.1:$port", code))
    fun connect(port: Int): AdbResult = run(listOf("connect", "127.0.0.1:$port"))
    fun shell(vararg cmd: String): AdbResult = run(listOf("shell") + cmd)   // 인자를 분리해 넘긴다(따옴표 문제 없음)
    fun killServer(): AdbResult = run(listOf("kill-server"), 10)
}
```
- 타임아웃 초과 시 `destroyForcibly`, `AdbResult(-1, "timeout")`.
- 코드는 어떤 로그에도 남기지 않는다. 출력은 `AdbOutcomes.maskCode`로 마스킹한 뒤에만 로그/화면에 쓴다.

**`adb/AdbOutcomes.kt`** (object, 순수, JVM 테스트)
```kotlin
object AdbOutcomes {
    fun pairSucceeded(r: AdbResult): Boolean       // exitCode == 0 && output에 "Successfully paired"
    fun connectSucceeded(r: AdbResult): Boolean    // exitCode == 0 && ("connected to" || "already connected to")
    fun grantSucceeded(r: AdbResult): Boolean      // exitCode == 0 && output에 "Error"/"Security exception" 없음
    fun maskCode(text: String): String             // 6자리 연속 숫자 → "******"
    fun failureMessage(phase: GrantPhase, r: AdbResult?): String   // 원인별 사용자 문구(§8)
}
```

**`adb/PortSelector.kt`** (순수, JVM 테스트) — 광고 목록에서 포트를 고른다.
```kotlin
data class Advert(val name: String, val port: Int, val seenAt: Long)
class PortSelector { fun offer(a: Advert); fun best(now: Long, maxAgeMs: Long = 60_000): Int? }  // 최신 seenAt 우선, port 0 무시, maxAge 초과 무시
```

**`adb/AdbPortDiscovery.kt`** — `NsdManager` 래퍼. `start()`가 두 타입(`_adb-tls-pairing._tcp`, `_adb-tls-connect._tcp`)의 discovery를 시작하고 `WifiManager.MulticastLock`을 잡는다. `onServiceFound`마다 `resolveService`(Android 14+: `registerServiceInfoCallback` 사용 가능하나 단순화를 위해 `resolveService` 직렬 큐), 결과를 `PortSelector` 두 개에 넣는다. `pairingPort()`/`connectPort()`는 `best(now)`. `stop()`이 discovery 중지·락 해제. 재시작 가능.

**`adb/AdbGrantState.kt`** (순수 + prefs)
```kotlin
enum class GrantPhase { IDLE, WAITING_CODE, PAIRING, CONNECTING, GRANTING, DONE, FAILED }
data class GrantStatus(val phase: GrantPhase, val message: String, val updatedAt: Long)
class AdbGrantPreferences(context) { fun snapshot(): GrantStatus; fun set(phase, message); registerListener/unregisterListener }   // prefs "adb_grant_state"
```

**`adb/AdbGrantMachine.kt`** (순수, JVM 테스트) — 이벤트 → 다음 단계와 사용자 문구.
```kotlin
sealed interface GrantEvent { Start; CodeEntered(code); PairingPortMissing; PairResult(ok); ConnectPortMissing; ConnectResult(ok); GrantResult(verified); Timeout; Cancel; BinaryMissing; WirelessDebuggingOff }
data class Transition(val phase: GrantPhase, val message: String)
object AdbGrantMachine { fun next(current: GrantPhase, event: GrantEvent): Transition }
```
문구(대표): WAITING_CODE "설정의 페어링 창을 열어 두고 알림에 6자리 코드를 입력하세요", PAIRING "페어링 중…", CONNECTING "연결 중…", GRANTING "권한 부여 중…", DONE "완료. 무선 디버깅을 꺼도 됩니다.", FAILED는 `failureMessage`.

**`adb/AdbGrantService.kt`** — 포그라운드 서비스(`foregroundServiceType="specialUse"`). 인텐트 액션: `START`, `CODE`(extra `code`), `MANUAL_PORTS`(extra `pairingPort`,`connectPort`), `CANCEL`.
- `START`: 사전 점검(바이너리, 무선 디버깅 설정값 `Settings.Global "adb_wifi_enabled"`, Wi-Fi 전송) → 실패면 FAILED 알림 후 종료. 통과면 `startForeground(대기 알림)`, discovery 시작, 10분 타이머.
- `CODE`: 단일 스레드 executor에서 흐름 실행: 페어링 포트(탐색 → 없으면 최대 15초 대기 → 수동 값 → 없으면 실패) → `pair` → 연결 포트(탐색 → 최대 20초 → 수동 → 실패) → `connect` → `shell appops set <pkg> WRITE_SMS allow` → `PermissionState.canDeleteSms(this)`로 검증 → DONE. 어느 단계든 실패면 FAILED(문구). finally에 `killServer()`, discovery 중지, `stopSelf()`. 모든 전이는 `AdbGrantPreferences.set`과 알림 갱신을 동시에 한다.
- `CANCEL`/타임아웃: FAILED("시간이 지나 중단했습니다. 다시 시도하세요.") 후 종료.

**`adb/AdbCodeReceiver.kt`** — 알림 `RemoteInput` 결과를 받아 `AdbGrantService`에 `CODE`로 전달(`startService`; 서비스는 이미 포그라운드).

**`adb/AdbGrantNotifications.kt`** — 채널 `adb_grant`(IMPORTANCE_HIGH, 소리 없음). `waiting()`: 제목 "페어링 코드 입력", 본문 "설정의 페어링 창에 뜬 6자리 숫자를 여기에 넣고 보내기", `RemoteInput`(키 `code`, 숫자 힌트) 액션 "보내기", 취소 액션. `progress(text)`, `done()`, `failed(text, retry 액션=앱 열기)`. 알림 ID 고정 1개.

**`ui/GuideScreen.kt`** + **`ui/GuideSteps.kt`** — 컴맹용 안내. `GuideStep(title, instruction, images: List<Int>, primary: GuideAction, check: ((Context) -> Boolean)?)`. `GuideAction.OpenSettings(action: String, component: ComponentName?)`, `GuideAction.StartGrant`, `GuideAction.Next`. 화면: 진행 표시 "n/5", 제목 24sp, 지시문 18sp, 이미지 카드(탭하면 전체 화면 확대), 하단 주/부 버튼, 자동 확인 단계는 `check`가 참이면 "확인됨" 초록 표시. 마지막에 접힌 "다른 방법(PC에서 adb)" 카드(명령 복사).

**리소스** `res/drawable-nodpi/guide_01_software_info.webp` … `guide_08_toggle_off.webp`(§6), `assets/licenses/adb-LICENSE.txt`(Apache-2.0 원문).

### 4.2 수정 파일

- `app/build.gradle.kts`: `minSdk = 30`, `ndk { abiFilters += "arm64-v8a" }`, `packaging { jniLibs { useLegacyPackaging = true } }`, `versionCode = 8`, `versionName = "1.2.0"`.
- `AndroidManifest.xml`: 권한 `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`; `<application android:extractNativeLibs="true">`; `<service android:name=".adb.AdbGrantService" android:exported="false" android:foregroundServiceType="specialUse"><property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Pairs with the phone's own wireless debugging to grant this app the WRITE_SMS app-op once"/></service>`; `<receiver android:name=".adb.AdbCodeReceiver" android:exported="false"/>`.
- `MainViewModel.kt`: `MonitorUiState.adbGrant: GrantStatus`, `AdbGrantPreferences` 리스너, `startAdbGrant()`, `cancelAdbGrant()`, `submitManualPorts(pairing, connect)`.
- `MainActivity.kt`: `AppScreen.GUIDE`; 알림 권한 런처(`POST_NOTIFICATIONS`) → 승인 후 `startForegroundService(START)`; 설정 화면 열기 헬퍼(액션·컴포넌트, 실패 시 개발자 옵션 화면으로 폴백); 온보딩·설정에 새 콜백.
- `ui/OnboardingScreen.kt`, `ui/SettingsScreen.kt`: `AdbPermissionSetupCard` → `AdbSelfGrantCard(status, granted, onStart, onOpenGuide, adbCommand, onCopyAdbCommand)`: 상태 문구, 버튼 "폰에서 권한 부여"(granted면 숨김)와 "안내 보기", 접힌 "다른 방법(PC에서 adb)".
- `ui/UiComponents.kt`: `AdbSelfGrantCard` 추가(기존 `AdbPermissionSetupCard`는 폴백 내부에서 재사용).
- `README.md`: 설치 절차를 "폰에서 권한 부여" 중심으로, PC adb는 대안으로. 동봉 adb 고지·페어링 키 위치·삭제 방법. 한계.

## 5. 데이터 흐름

1. 온보딩/설정 카드 "폰에서 권한 부여" → 알림 권한 요청 → `AdbGrantService START`.
2. 서비스: 사전 점검 → 대기 알림(코드 입력칸) + discovery 시작. 안내 화면은 3·4단계를 보여 준다.
3. 사용자: 설정 → 개발자 옵션 → 무선 디버깅 → "페어링 코드로 기기 페어링" → 알림창을 내려 코드 입력.
4. `AdbCodeReceiver` → `CODE` → 서비스가 페어링 포트 확보 → `pair` → 연결 포트 확보 → `connect` → `appops set` → `canDeleteSms` 검증 → DONE 알림 → prefs 갱신 → 홈·온보딩 카드 "메시지함 삭제: 정상".
5. 완료 안내: "무선 디버깅을 꺼도 됩니다."

## 6. 안내 화면 콘텐츠

단계(접은 화면 기준, 문구는 화면의 실제 글자를 따옴표로):
0. 시작: "광고 문자를 지우려면 폰의 숨은 설정 하나가 필요합니다. 5단계, 약 3분, PC 없이 됩니다." 버튼 "시작".
1. 개발자 옵션: "설정 → '휴대전화 정보' → '소프트웨어 정보' → '빌드번호'를 7번 연속 톡톡 누르세요. '개발자 모드를 켰습니다'가 뜨면 성공." 버튼 "휴대전화 정보 열기"(`Settings.ACTION_DEVICE_INFO_SETTINGS`). 이미지 `guide_01_software_info`, `guide_02_build_number`. 확인: `Settings.Global.getInt(DEVELOPMENT_SETTINGS_ENABLED)==1`.
2. 무선 디버깅: "설정 → '개발자 옵션' → '무선 디버깅' 스위치를 켜고 확인창에서 '허용'." 버튼 "개발자 옵션 열기"(`ACTION_APPLICATION_DEVELOPMENT_SETTINGS`; 무선 디버깅 화면 컴포넌트가 이 폰에 있으면 그리로). 이미지 `guide_03_dev_wireless_switch`, `guide_04_wireless_allow`. 확인: `adb_wifi_enabled==1`.
3. 페어링 창: "'무선 디버깅' 글자를 눌러 들어가 '페어링 코드로 기기 페어링'을 누르세요. 6자리 숫자가 뜹니다. 이 창을 닫지 마세요." 버튼 "권한 부여 시작"(서비스 START → 알림). 이미지 `guide_05_wireless_page`, `guide_06_pairing_dialog`(코드 마스킹).
4. 코드 입력: "화면 맨 위를 아래로 쓸어내려 알림창을 열고, '문자함 정리' 알림의 칸에 6자리를 넣고 '보내기'." 이미지 `guide_07_notification_input`. 상태 문구는 서비스 상태를 그대로 표시.
5. 완료: "완료! 이제 무선 디버깅을 꺼도 됩니다. 설정 → '개발자 옵션' → '무선 디버깅' 끄기." 버튼 "개발자 옵션 열기". 이미지 `guide_08_toggle_off`. 실패 시 원인 문구와 "다시 시도"(3단계로).

이미지 제작: 이 폰에서 각 화면을 adb로 열고 `screencap`으로 캡처(접은 화면). IP·코드·계정·기기명은 PIL로 마스킹, 누를 곳은 빨간 박스(선 4px, 모서리 8px, 기존 매뉴얼 규격). WebP 품질 80, 긴 변 1080px 이하, 장당 200KB 이하. 파일명은 위 표기. 캡처가 불가한 단계(빌드번호 7번 탭 토스트 등)는 정적 캡처 1장으로 대신한다.

## 7. 권한과 매니페스트

- `POST_NOTIFICATIONS`: 부여 시작 버튼에서 런타임 요청. 거부 시 "알림을 허용해야 코드를 입력할 수 있습니다" + 앱 설정 열기.
- 포그라운드 서비스 `specialUse` + PROPERTY 설명 문자열. 시작은 사용자 탭 직후(액티비티)라 Android 14+ 제약을 만족한다.
- 무선 디버깅·개발자 옵션 값은 `Settings.Global` 읽기만(권한 불필요).
- `CHANGE_WIFI_MULTICAST_STATE`로 mDNS 수신 보장.

## 8. 오류 처리와 보안

| 상황 | 감지 | 사용자 문구 |
|---|---|---|
| 바이너리 없음/실행 불가 | `isAvailable()` false | "이 기기에서는 지원되지 않습니다. 아래 'PC에서 adb' 방법을 쓰세요." |
| 무선 디버깅 꺼짐 | `adb_wifi_enabled != 1` | "무선 디버깅이 꺼져 있습니다. 2단계를 확인하세요." |
| Wi-Fi 없음 | `NetworkCapabilities.TRANSPORT_WIFI` 없음 | "Wi-Fi에 연결한 뒤 다시 시도하세요." |
| 페어링 포트 못 찾음 | 15초 내 광고 없음, 수동값 없음 | "페어링 창이 열려 있는지 확인하세요. 안 되면 '포트 직접 입력'." |
| 페어링 실패 | `pairSucceeded` false | "코드가 틀렸거나 창이 닫혔습니다. 3단계부터 다시 하세요." |
| 연결 포트 못 찾음/연결 실패 | 20초 내 없음 / `connectSucceeded` false | "연결에 실패했습니다. 무선 디버깅을 껐다 켜고 다시 시도하세요." |
| 부여 실패 | `grantSucceeded` false 또는 검증 false | "권한 부여에 실패했습니다: <마스킹된 출력 1줄>" |
| 시간 초과(10분) | 타이머 | "시간이 지나 중단했습니다. 다시 시도하세요." |

보안: 서비스는 고정된 명령만 실행하고 외부 입력을 명령에 넣지 않는다(코드는 `pair`의 인자로만). 코드·포트는 로그·prefs·이력에 저장하지 않는다. 페어링 키(`files/adbhome/.android/adbkey*`)는 앱 내부에만 있고, 사용자는 설정의 "페어링된 기기"에서 이 앱 항목을 지울 수 있음을 README에 적는다. 서비스 종료 시 항상 `kill-server`.

## 9. 테스트와 검증

JVM 단위(Android 런타임 불필요):
- `AdbOutcomesTest`: 페어링/연결/부여 성공 판정 6종, `maskCode`(6자리만 마스킹, 5·7자리 유지), `failureMessage` 단계별.
- `PortSelectorTest`: 최신 우선, port 0 무시, maxAge 초과 무시, 비어 있으면 null.
- `AdbGrantMachineTest`: 정상 경로 전이 6단계, 실패 이벤트 4종 → FAILED와 문구, IDLE에서 CodeEntered 무시.

빌드: `./gradlew --offline testDebugUnitTest assembleDebug`(minSdk 30, jniLibs 포함 확인 `unzip -l | grep libadb.so`).

실기기 검증(사용자 지시대로 삭제 후 재설치):
1. `adb uninstall com.interbb.disasterinboxcleaner` → `adb install app-debug.apk` (appops·권한·알림 접근 모두 초기화됨; 이전 AdbProbe도 제거).
2. 온보딩: 알림 접근 허용, 문자 권한 요청, 3단계 "안내 보기"로 안내 화면 확인 → "폰에서 권한 부여" → 알림 권한 허용 → 사용자가 무선 디버깅 켜기 → 페어링 창 열기 → 알림에 코드 입력.
3. 확인: 알림 "완료", 온보딩 3단계 "승인됨", 호스트 adb `appops get … WRITE_SMS` = allow, `settings get global adb_wifi_enabled` 값은 사용자가 끈 뒤 0.
4. 실패 경로 1종 이상(틀린 코드) 문구 확인.
5. 설정 완료 → 스위치 켜기 → 기존 기능 회귀(광고 문자 정리 화면 진입).

## 10. 한계

- 개발자 옵션·무선 디버깅·페어링 창 열기·코드 입력은 사용자 손으로. Wi-Fi 필요. Android 11+, arm64 전용. 안내 이미지는 이 폰(One UI 9, 접은 화면) 기준.
- Shizuku가 떠 있어도 서버 포트를 달리 써 충돌을 피하지만, 무선 디버깅은 하나의 페어링 창만 열 수 있다.

## 11. 후속 후보

- 페어링 창 자동 열기(설정 딥링크 컴포넌트 확인 후), 접근성 서비스로 코드 자동 읽기, 다른 ABI 바이너리, 안내 이미지 펼친 화면 버전.
