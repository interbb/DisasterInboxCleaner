package com.interbb.disasterinboxcleaner.ui

import com.interbb.disasterinboxcleaner.adb.GrantMessages
import com.interbb.disasterinboxcleaner.adb.GrantPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tutorial's flow, faces, plates and wording as pure data: no Robolectric, no Context. Also
 * carries over the port-versus-code matching cases the old GuideStepsTest covered, which now live
 * in [TutorialGrantText.asksForPort].
 */
class TutorialModelTest {
    private val fresh = TutorialContext()
    private val allGranted = TutorialContext(
        smsReadGranted = true,
        notificationAccessGranted = true,
        smsDeleteGranted = true,
    )

    // A phone with the three permissions and the battery exemption all in hand - the only state
    // that should ever see just WELCOME and DONE.
    private val fullySetUp = allGranted.copy(batteryExempt = true)

    // 1. order and count

    @Test
    fun theFlowIsEightScreensInOrder() {
        assertEquals(
            listOf(
                TutorialStep.WELCOME,
                TutorialStep.SMS_READ,
                TutorialStep.NOTIFICATION_ACCESS,
                TutorialStep.DEVELOPER_OPTIONS,
                TutorialStep.WIRELESS_DEBUGGING,
                TutorialStep.DELETE_GRANT,
                TutorialStep.BATTERY_EXEMPTION,
                TutorialStep.DONE,
            ),
            TutorialModel.allSteps,
        )
    }

    // 2-4. skip rule

    @Test
    fun aFactoryFreshPhoneSeesEveryScreen() {
        assertEquals(TutorialModel.allSteps, TutorialModel.visibleSteps(fresh, emptySet()))
    }

    @Test
    fun aFullySetUpPhoneSeesOnlyWelcomeAndDone() {
        assertEquals(
            listOf(TutorialStep.WELCOME, TutorialStep.DONE),
            TutorialModel.visibleSteps(fullySetUp, emptySet()),
        )
    }

    @Test
    fun eachSatisfiedConditionDropsItsOwnScreen() {
        val devOn = TutorialModel.visibleSteps(fresh.copy(developerOptionsOn = true), emptySet())
        assertFalse(TutorialStep.DEVELOPER_OPTIONS in devOn)
        assertTrue(TutorialStep.WIRELESS_DEBUGGING in devOn)

        val wirelessOn = TutorialModel.visibleSteps(fresh.copy(wirelessDebuggingOn = true), emptySet())
        assertFalse(TutorialStep.WIRELESS_DEBUGGING in wirelessOn)
        assertTrue(TutorialStep.DEVELOPER_OPTIONS in wirelessOn)

        val deleteGranted = TutorialModel.visibleSteps(fresh.copy(smsDeleteGranted = true), emptySet())
        assertFalse(TutorialStep.DEVELOPER_OPTIONS in deleteGranted)
        assertFalse(TutorialStep.WIRELESS_DEBUGGING in deleteGranted)
        assertFalse(TutorialStep.DELETE_GRANT in deleteGranted)
        // The exemption screen is its own, independent condition - granting the delete permission
        // must not also skip it.
        assertTrue(TutorialStep.BATTERY_EXEMPTION in deleteGranted)

        val smsRead = TutorialModel.visibleSteps(fresh.copy(smsReadGranted = true), emptySet())
        assertFalse(TutorialStep.SMS_READ in smsRead)

        val notifications = TutorialModel.visibleSteps(fresh.copy(notificationAccessGranted = true), emptySet())
        assertFalse(TutorialStep.NOTIFICATION_ACCESS in notifications)

        val batteryOn = TutorialModel.visibleSteps(fresh.copy(batteryExempt = true), emptySet())
        assertFalse(TutorialStep.BATTERY_EXEMPTION in batteryOn)
        assertTrue(TutorialStep.DELETE_GRANT in batteryOn)
    }

    @Test
    fun theBatteryExemptionScreenIsSkippedOnlyOnceGranted() {
        assertFalse(TutorialModel.isSkippable(TutorialStep.BATTERY_EXEMPTION, fresh))
        assertTrue(TutorialModel.isSkippable(TutorialStep.BATTERY_EXEMPTION, fresh.copy(batteryExempt = true)))
        assertFalse(TutorialModel.isSatisfied(TutorialStep.BATTERY_EXEMPTION, fresh))
        assertTrue(TutorialModel.isSatisfied(TutorialStep.BATTERY_EXEMPTION, fresh.copy(batteryExempt = true)))
    }

    @Test
    fun theBatteryExemptionScreenSitsBetweenDeleteGrantAndDone() {
        val steps = TutorialModel.allSteps
        val deleteIndex = steps.indexOf(TutorialStep.DELETE_GRANT)
        val batteryIndex = steps.indexOf(TutorialStep.BATTERY_EXEMPTION)
        val doneIndex = steps.indexOf(TutorialStep.DONE)
        assertEquals(deleteIndex + 1, batteryIndex)
        assertEquals(batteryIndex + 1, doneIndex)

        // The sole automatic move in the flow (the grant landing) hands over to the exemption
        // screen next while it is still missing, not straight to DONE.
        assertEquals(
            TutorialStep.BATTERY_EXEMPTION,
            TutorialModel.next(TutorialStep.DELETE_GRANT, fresh.copy(smsDeleteGranted = true), emptySet()),
        )
        assertEquals(
            TutorialStep.DONE,
            TutorialModel.next(
                TutorialStep.BATTERY_EXEMPTION,
                fresh.copy(smsDeleteGranted = true, batteryExempt = true),
                emptySet(),
            ),
        )
    }

    // 5. a screen you are standing on never disappears

    @Test
    fun aScreenAlreadyShownStaysInTheListEvenOnceSatisfied() {
        val ctx = fresh.copy(wirelessDebuggingOn = true)
        val visible = TutorialModel.visibleSteps(ctx, setOf(TutorialStep.WIRELESS_DEBUGGING))
        assertTrue(TutorialStep.WIRELESS_DEBUGGING in visible)
    }

    @Test
    fun nextAndPreviousNeverStrandTheCurrentScreen() {
        // Standing on a screen that just became skippable and was never recorded as shown.
        val ctx = fresh.copy(developerOptionsOn = true)
        assertEquals(
            TutorialStep.WIRELESS_DEBUGGING,
            TutorialModel.next(TutorialStep.DEVELOPER_OPTIONS, ctx, emptySet()),
        )
        assertEquals(
            TutorialStep.NOTIFICATION_ACCESS,
            TutorialModel.previous(TutorialStep.DEVELOPER_OPTIONS, ctx, emptySet()),
        )
        assertNull(TutorialModel.next(TutorialStep.DONE, ctx, emptySet()))
        assertNull(TutorialModel.previous(TutorialStep.WELCOME, ctx, emptySet()))
    }

    // 6. progress never moves backwards

    @Test
    fun progressDoesNotMoveBackwardsWhileWalkingForward() {
        // Developer options starts ON, hiding that screen from the very first render - the walk
        // then turns it OFF right after the first step, reviving a screen ahead of the user while
        // they keep moving, alongside the other flags turning ON as the old test already covered.
        var ctx = fresh.copy(developerOptionsOn = true)
        var current = TutorialStep.WELCOME
        var shown = setOf(current)
        var everVisible = TutorialModel.growVisible(current, ctx, setOf(current))
        var last = TutorialModel.progress(current, everVisible)
        val flips = listOf<(TutorialContext) -> TutorialContext>(
            { it.copy(developerOptionsOn = false) },
            { it.copy(smsReadGranted = true) },
            { it.copy(notificationAccessGranted = true) },
            { it.copy(wirelessDebuggingOn = true) },
            { it.copy(smsDeleteGranted = true) },
            { it.copy(batteryExempt = true) },
        )
        var flipped = 0
        while (true) {
            val next = TutorialModel.next(current, ctx, shown) ?: break
            current = next
            shown = shown + next
            if (flipped < flips.size) {
                ctx = flips[flipped](ctx)
                flipped++
            }
            everVisible = TutorialModel.growVisible(current, ctx, everVisible)
            val now = TutorialModel.progress(current, everVisible)
            assertTrue("progress fell from $last to $now at $current", now >= last)
            last = now
        }
        assertEquals(TutorialStep.DONE, current)
        assertEquals(1f, last, 0.0001f)
    }

    @Test
    fun progressIsMeasuredAgainstTheScreensThisPhoneShows() {
        // Two screens on a phone that is already set up, so the first one is one half.
        val atWelcome = TutorialModel.growVisible(TutorialStep.WELCOME, fullySetUp, emptySet())
        assertEquals(0.5f, TutorialModel.progress(TutorialStep.WELCOME, atWelcome), 0.0001f)
        val atDone = TutorialModel.growVisible(TutorialStep.DONE, fullySetUp, emptySet())
        assertEquals(1f, TutorialModel.progress(TutorialStep.DONE, atDone), 0.0001f)
    }

    @Test
    fun progressDoesNotChangeWhileStandingStillEvenIfAScreenAheadRevives() {
        // The hazard: the user stands on the welcome screen while a screen AHEAD of them revives
        // (developer options turned back off), which would grow the denominator without moving the
        // numerator. The accumulator avoids that by merging only at navigation, so this test shows
        // both halves — that merging now WOULD drop the bar, and that not merging holds it steady.
        val ctxDevOn = fresh.copy(developerOptionsOn = true)
        val everVisible = TutorialModel.growVisible(TutorialStep.WELCOME, ctxDevOn, setOf(TutorialStep.WELCOME))
        val before = TutorialModel.progress(TutorialStep.WELCOME, everVisible)

        val ctxDevOff = fresh.copy(developerOptionsOn = false)
        val merged = TutorialModel.growVisible(TutorialStep.WELCOME, ctxDevOff, everVisible)
        assertTrue(
            "the scenario is meaningless unless turning the flag off actually revives a screen",
            merged.size > everVisible.size,
        )
        assertTrue(
            "merging a screen revived ahead of the user is exactly what would drop the bar",
            TutorialModel.progress(TutorialStep.WELCOME, merged) < before,
        )

        // TutorialScreen does not merge while the user stands still, so what it shows is still
        // computed from the un-grown set.
        assertEquals(before, TutorialModel.progress(TutorialStep.WELCOME, everVisible), 0.0001f)
    }

    // 7. face mapping

    @Test
    fun everyPhaseMapsToTheFaceTheSpecTableGives() {
        for (phase in GrantPhase.entries) {
            for (blocked in listOf(false, true)) {
                for (granted in listOf(false, true)) {
                    val ctx = fresh.copy(
                        grantPhase = phase,
                        notificationsBlocked = blocked,
                        smsDeleteGranted = granted,
                    )
                    val expected = when {
                        granted -> GrantFace.GRANTED
                        // Blocked only wins while idle - a live phase must never be hidden by it.
                        blocked && phase == GrantPhase.IDLE -> GrantFace.NOTIFICATIONS_BLOCKED
                        phase == GrantPhase.WAITING_CODE -> GrantFace.WAITING_CODE
                        phase == GrantPhase.WAITING_CONNECT_PORT -> GrantFace.WAITING_PORT
                        phase == GrantPhase.PAIRING -> GrantFace.WORKING
                        phase == GrantPhase.CONNECTING -> GrantFace.WORKING
                        phase == GrantPhase.GRANTING -> GrantFace.WORKING
                        phase == GrantPhase.FAILED -> GrantFace.FAILED
                        else -> GrantFace.READY
                    }
                    assertEquals("$phase blocked=$blocked granted=$granted", expected, TutorialModel.grantFace(ctx))
                }
            }
        }
    }

    @Test
    fun theGrantedFaceBeatsEveryPhaseAndBlockedBeatsIdle() {
        assertEquals(
            GrantFace.GRANTED,
            TutorialModel.grantFace(fresh.copy(smsDeleteGranted = true, grantPhase = GrantPhase.FAILED)),
        )
        assertEquals(
            GrantFace.NOTIFICATIONS_BLOCKED,
            TutorialModel.grantFace(fresh.copy(notificationsBlocked = true, grantPhase = GrantPhase.IDLE)),
        )
        assertEquals(GrantFace.READY, TutorialModel.grantFace(fresh.copy(grantPhase = GrantPhase.DONE)))
    }

    @Test
    fun notificationsBlockedWhileASessionIsInFlightStillOffersAWayToCancel() {
        // Notifications turning off mid-pairing must not swallow the cancel button: a live phase
        // always wins over the blocked face, and every live phase's secondary is 진행 취소.
        val livePhases = listOf(
            GrantPhase.WAITING_CODE,
            GrantPhase.WAITING_CONNECT_PORT,
            GrantPhase.PAIRING,
            GrantPhase.CONNECTING,
            GrantPhase.GRANTING,
        )
        for (phase in livePhases) {
            val ctx = fresh.copy(notificationsBlocked = true, grantPhase = phase)
            val face = TutorialModel.grantFace(ctx)
            assertFalse("blocked face swallowed the live phase $phase", face == GrantFace.NOTIFICATIONS_BLOCKED)
            assertEquals(
                "no cancel offered for $phase while notifications are blocked",
                TutorialCopy.GRANT_CANCEL,
                TutorialCopy.secondaryLabel(TutorialStep.DELETE_GRANT, face, ctx),
            )
        }
    }

    // 8-9. plates and captions

    @Test
    fun eachFaceShowsExactlyThePlatesItNeeds() {
        fun keys(face: GrantFace) =
            TutorialModel.plates(TutorialStep.DELETE_GRANT, face, fresh).map { it.key }

        assertEquals(listOf("guide_05", "guide_06", "guide_07"), keys(GrantFace.WAITING_CODE))
        assertEquals(listOf("guide_05"), keys(GrantFace.WAITING_PORT))
        assertEquals(listOf("guide_07"), keys(GrantFace.READY))
        assertEquals(emptyList<String>(), keys(GrantFace.WORKING))
        assertEquals(emptyList<String>(), keys(GrantFace.FAILED))
        assertEquals(emptyList<String>(), keys(GrantFace.GRANTED))
    }

    @Test
    fun theWholeFlowReferencesExactlyEightDistinctCaptures() {
        assertEquals(8, allPlates().map { it.key }.toSet().size)
    }

    @Test
    fun everyPlateCarriesACaption() {
        for (plate in allPlates()) {
            assertTrue("empty caption on ${plate.key}", plate.caption.isNotBlank())
        }
    }

    @Test
    fun theSameCaptureCarriesTheCaptionItsFaceNeeds() {
        val pairing = TutorialModel.plates(TutorialStep.DELETE_GRANT, GrantFace.WAITING_CODE, fresh).first()
        val port = TutorialModel.plates(TutorialStep.DELETE_GRANT, GrantFace.WAITING_PORT, fresh).first()
        assertEquals("guide_05", pairing.key)
        assertEquals("guide_05", port.key)
        assertFalse(pairing.caption == port.caption)
    }

    @Test
    fun aScreenAlreadySatisfiedDropsItsInstructionCaptures() {
        assertTrue(
            TutorialModel.plates(
                TutorialStep.DEVELOPER_OPTIONS,
                GrantFace.READY,
                fresh.copy(developerOptionsOn = true),
            ).isEmpty(),
        )
        assertEquals(
            2,
            TutorialModel.plates(TutorialStep.DEVELOPER_OPTIONS, GrantFace.READY, fresh).size,
        )
    }

    // 10. code-only versus port-and-code, carried over from GuideStepsTest

    @Test
    fun theDefaultPairingCopyAsksForTheCodeAlone() {
        // The service now starts at WAITING_CODE_ONLY, so this is the first-run path.
        val ctx = fresh.copy(grantPhase = GrantPhase.WAITING_CODE, grantMessage = GrantMessages.WAITING_CODE_ONLY)
        assertFalse(TutorialGrantText.asksForPort(ctx.grantMessage))
        assertEquals(TutorialCopy.GRANT_CODE_ONLY_BODY, TutorialCopy.grantBody(GrantFace.WAITING_CODE, ctx))
    }

    @Test
    fun theEscalatedMessageAsksForThePortToo() {
        val ctx = fresh.copy(grantPhase = GrantPhase.WAITING_CODE, grantMessage = GrantMessages.WAITING)
        assertTrue(TutorialGrantText.asksForPort(ctx.grantMessage))
        assertEquals(TutorialCopy.GRANT_PORT_AND_CODE_BODY, TutorialCopy.grantBody(GrantFace.WAITING_CODE, ctx))
    }

    @Test
    fun anInvalidReplyComposedOntoTheEscalatedMessageStillAsksForThePort() {
        // The exact shape AdbGrantService.showInvalid produces when no pairing port is known yet.
        assertTrue(
            TutorialGrantText.asksForPort(
                GrantMessages.composeInvalidReply("코드는 6자리입니다.", GrantMessages.WAITING),
            ),
        )
        // This composition drops the duplicated example tail from the base message.
        assertTrue(
            TutorialGrantText.asksForPort(
                GrantMessages.composeInvalidReply(
                    "포트와 코드를 함께 입력하거나 코드만 입력하세요. ${GrantMessages.PORT_CODE_EXAMPLE}",
                    GrantMessages.WAITING,
                ),
            ),
        )
    }

    @Test
    fun anInvalidReplyComposedOntoTheCodeOnlyMessageDoesNotAskForThePort() {
        assertFalse(
            TutorialGrantText.asksForPort(
                GrantMessages.composeInvalidReply("코드는 6자리입니다.", GrantMessages.WAITING_CODE_ONLY),
            ),
        )
    }

    @Test
    fun unrelatedMessagesDoNotAskForThePort() {
        assertFalse(TutorialGrantText.asksForPort(GrantMessages.WAITING_CONNECT_PORT))
        assertFalse(TutorialGrantText.asksForPort(GrantMessages.DONE))
        assertFalse(TutorialGrantText.asksForPort(""))
    }

    // 11. failure mapping

    @Test
    fun everyServiceFailureIsRestatedWithoutANumberedStep() {
        val failures = listOf(
            GrantMessages.WIRELESS_OFF,
            GrantMessages.PAIR_FAILED,
            GrantMessages.CONNECT_FAILED,
            GrantMessages.NO_WIFI,
            GrantMessages.BINARY_MISSING,
            GrantMessages.TIMEOUT,
            GrantMessages.STALE_SESSION,
            GrantMessages.CANCELLED,
            GrantMessages.NOT_WAITING,
            GrantMessages.SERVICE_START_FAILED,
            GrantMessages.GRANT_FAILED_PREFIX + "appops 명령이 실패했습니다",
            "",
        )
        for (message in failures) {
            val shown = TutorialGrantText.failure(message)
            assertTrue("empty mapping for '$message'", shown.isNotBlank())
            assertFalse("'$shown' still names a step", shown.contains("단계"))
        }
    }

    @Test
    fun anUnknownFailurePassesThroughUnlessItNamesAStep() {
        assertEquals("알 수 없는 오류", TutorialGrantText.failure("알 수 없는 오류"))
        assertEquals(TutorialGrantText.GENERIC_FAILURE, TutorialGrantText.failure("2단계를 확인하세요."))
    }

    @Test
    fun aGrantFailureKeepsItsDetailOnASecondLine() {
        val message = GrantMessages.GRANT_FAILED_PREFIX + "appops 명령이 실패했습니다"
        assertEquals(TutorialGrantText.GENERIC_FAILURE, TutorialGrantText.failure(message))
        assertEquals("appops 명령이 실패했습니다", TutorialGrantText.failureDetail(message))
        assertNull(TutorialGrantText.failureDetail(GrantMessages.TIMEOUT))
    }

    @Test
    fun onlyTheWirelessOffFailureOffersTheWayBack() {
        val wirelessOff = fresh.copy(grantPhase = GrantPhase.FAILED, grantMessage = GrantMessages.WIRELESS_OFF)
        assertEquals(
            TutorialCopy.GRANT_FAILED_BACK_TO_WIRELESS,
            TutorialCopy.secondaryLabel(TutorialStep.DELETE_GRANT, GrantFace.FAILED, wirelessOff),
        )
        val pairFailed = fresh.copy(grantPhase = GrantPhase.FAILED, grantMessage = GrantMessages.PAIR_FAILED)
        assertNull(TutorialCopy.secondaryLabel(TutorialStep.DELETE_GRANT, GrantFace.FAILED, pairFailed))
    }

    @Test
    fun progressLinesCoverOnlyTheWorkingPhases() {
        assertEquals(TutorialGrantText.PAIRING, TutorialGrantText.progress(GrantPhase.PAIRING))
        assertEquals(TutorialGrantText.CONNECTING, TutorialGrantText.progress(GrantPhase.CONNECTING))
        assertEquals(TutorialGrantText.GRANTING, TutorialGrantText.progress(GrantPhase.GRANTING))
        assertNull(TutorialGrantText.progress(GrantPhase.IDLE))
        assertNull(TutorialGrantText.progress(GrantPhase.WAITING_CODE))
        assertNull(TutorialGrantText.progress(GrantPhase.DONE))
    }

    // 12. satisfaction

    @Test
    fun eachScreenFollowsItsOwnStateField() {
        assertTrue(TutorialModel.isSatisfied(TutorialStep.WELCOME, fresh))
        assertTrue(TutorialModel.isSatisfied(TutorialStep.DONE, fresh))
        assertFalse(TutorialModel.isSatisfied(TutorialStep.SMS_READ, fresh))
        assertTrue(TutorialModel.isSatisfied(TutorialStep.SMS_READ, fresh.copy(smsReadGranted = true)))
        assertTrue(
            TutorialModel.isSatisfied(
                TutorialStep.NOTIFICATION_ACCESS,
                fresh.copy(notificationAccessGranted = true),
            ),
        )
        assertTrue(
            TutorialModel.isSatisfied(TutorialStep.DEVELOPER_OPTIONS, fresh.copy(developerOptionsOn = true)),
        )
        assertTrue(
            TutorialModel.isSatisfied(TutorialStep.WIRELESS_DEBUGGING, fresh.copy(wirelessDebuggingOn = true)),
        )
        assertTrue(TutorialModel.isSatisfied(TutorialStep.DELETE_GRANT, fresh.copy(smsDeleteGranted = true)))
    }

    @Test
    fun aSatisfiedScreenSwitchesToItsConfirmationAndNextButton() {
        val ctx = fresh.copy(smsReadGranted = true)
        assertEquals(TutorialCopy.SMS_READ_SATISFIED, TutorialCopy.body(TutorialStep.SMS_READ, GrantFace.READY, ctx))
        assertEquals(TutorialCopy.NEXT, TutorialCopy.primaryLabel(TutorialStep.SMS_READ, GrantFace.READY, ctx))
        assertNull(TutorialCopy.hint(TutorialStep.SMS_READ, GrantFace.READY, ctx))
        // The app-info way out is offered whenever the permission is still missing, because a
        // twice-denied permission makes the primary button look inert.
        assertEquals(
            TutorialCopy.SMS_READ_SECONDARY,
            TutorialCopy.secondaryLabel(TutorialStep.SMS_READ, GrantFace.READY, fresh),
        )
        assertNull(TutorialCopy.secondaryLabel(TutorialStep.SMS_READ, GrantFace.READY, ctx))
    }

    // 13. the conditional lines of the finish screen

    @Test
    fun theWirelessOffLineAppearsOnlyOnceThePermissionIsInHand() {
        assertFalse(TutorialModel.showsWirelessOffNote(fresh.copy(wirelessDebuggingOn = true)))
        assertFalse(TutorialModel.showsWirelessOffNote(fresh.copy(smsDeleteGranted = true)))
        assertTrue(
            TutorialModel.showsWirelessOffNote(fresh.copy(smsDeleteGranted = true, wirelessDebuggingOn = true)),
        )
        assertEquals(
            listOf("guide_08"),
            TutorialModel.plates(
                TutorialStep.DONE,
                GrantFace.GRANTED,
                fresh.copy(smsDeleteGranted = true, wirelessDebuggingOn = true),
            ).map { it.key },
        )
    }

    @Test
    fun theRemainingItemsLineNamesExactlyWhatIsMissing() {
        assertTrue(TutorialModel.showsMissingNote(fresh))
        assertFalse(TutorialModel.showsMissingNote(fullySetUp))
        assertEquals(
            listOf(
                TutorialCopy.LABEL_SMS_READ,
                TutorialCopy.LABEL_NOTIFICATION_ACCESS,
                TutorialCopy.LABEL_SMS_DELETE,
                TutorialCopy.LABEL_BATTERY_EXEMPTION,
            ),
            TutorialModel.missingLabels(fresh),
        )
        assertEquals(
            listOf(TutorialCopy.LABEL_SMS_DELETE, TutorialCopy.LABEL_BATTERY_EXEMPTION),
            TutorialModel.missingLabels(
                fresh.copy(smsReadGranted = true, notificationAccessGranted = true),
            ),
        )
        assertEquals(emptyList<String>(), TutorialModel.missingLabels(fullySetUp))
        assertNull(TutorialCopy.missingSentence(fullySetUp))
        assertNotNull(TutorialCopy.missingSentence(fresh))

        // The one case the DONE screen's disabled button must still explain on its own: every
        // permission granted but the exemption missing. The dedicated BATTERY_EXEMPTION screen
        // normally resolves this before DONE is ever reached, but the line must still name it if
        // DONE is somehow reached without it anyway.
        assertTrue(TutorialModel.showsMissingNote(allGranted))
        assertEquals(listOf(TutorialCopy.LABEL_BATTERY_EXEMPTION), TutorialModel.missingLabels(allGranted))
    }

    // 14. the completion gate, with the battery exemption required

    @Test
    fun completionNeedsTheBatteryExemptionAsWellAsThePermissions() {
        // Matches MonitorUiState.setupReady, which gates MainViewModel.completeOnboarding().
        assertFalse(TutorialModel.canComplete(fresh))
        assertFalse(TutorialModel.canComplete(allGranted))
        assertTrue(TutorialModel.canComplete(fullySetUp))
    }

    @Test
    fun theFinishButtonSaysWhatItWillDo() {
        assertEquals(
            TutorialCopy.DONE_PRIMARY_INCOMPLETE,
            TutorialCopy.primaryLabel(TutorialStep.DONE, GrantFace.READY, fresh),
        )
        assertTrue(TutorialModel.donePrimaryEnabled(fresh))
        assertEquals(
            TutorialStep.SMS_READ,
            TutorialModel.firstUnsatisfied(fresh, setOf(TutorialStep.WELCOME, TutorialStep.DONE)),
        )

        // Permissions in hand but no exemption: the label already reads as ready (it only checks
        // the three core permissions), yet the button stays disabled - normally the dedicated
        // BATTERY_EXEMPTION screen catches this before DONE is ever reached, and firstUnsatisfied
        // points there for the case it is not.
        assertEquals(
            TutorialCopy.DONE_PRIMARY,
            TutorialCopy.primaryLabel(TutorialStep.DONE, GrantFace.READY, allGranted),
        )
        assertFalse(TutorialModel.donePrimaryEnabled(allGranted))
        assertEquals(TutorialStep.BATTERY_EXEMPTION, TutorialModel.firstUnsatisfied(allGranted, emptySet()))
        assertNull(TutorialModel.firstUnsatisfied(fullySetUp, emptySet()))

        assertTrue(TutorialModel.donePrimaryEnabled(fullySetUp))
    }

    // 15. tone

    @Test
    fun everyStringIsQuietDeclarativeKorean() {
        val strings = copyStrings()
        // Guards against the reflection silently finding nothing and the checks below passing vacuously.
        assertTrue("only ${strings.size} strings found", strings.size >= 50)
        for ((name, value) in strings) {
            assertFalse("$name has an exclamation mark", value.contains("!"))
            assertFalse("$name says 휴대전화 정보", value.contains("휴대전화 정보"))
            assertFalse("$name points at a numbered step", NUMBERED_STEP.containsMatchIn(value))
            assertFalse(
                "$name contains an emoji",
                // Character.isSurrogate alone only catches non-BMP emoji; the Miscellaneous
                // Symbols and Dingbats blocks (0x2600-0x27BF) cover BMP marks like ✓, ⚠ and ★.
                value.any { Character.isSurrogate(it) || it.code in 0x2600..0x27BF },
            )
        }
    }

    @Test
    fun everyScreenHasATitleAndAPrimaryButton() {
        val contexts = listOf(fresh, allGranted, fullySetUp)
        for (ctx in contexts) {
            for (step in TutorialModel.allSteps) {
                for (face in GrantFace.entries) {
                    assertTrue(TutorialCopy.title(step).isNotBlank())
                    assertTrue(TutorialCopy.body(step, face, ctx).isNotBlank())
                    assertTrue(TutorialCopy.primaryLabel(step, face, ctx).isNotBlank())
                }
            }
        }
    }

    private fun allPlates(): List<TutorialPlate> {
        val contexts = listOf(fresh, fresh.copy(smsDeleteGranted = true, wirelessDebuggingOn = true))
        return buildList {
            for (ctx in contexts) {
                for (step in TutorialModel.allSteps) {
                    for (face in GrantFace.entries) {
                        addAll(TutorialModel.plates(step, face, ctx))
                    }
                }
            }
        }
    }

    /** Every string constant of the tutorial's own wording, by field name. */
    private fun copyStrings(): List<Pair<String, String>> =
        listOf(TutorialCopy, TutorialGrantText).flatMap { holder ->
            holder.javaClass.declaredFields
                .filter { it.type == String::class.java }
                .map { field ->
                    field.isAccessible = true
                    "${holder.javaClass.simpleName}.${field.name}" to (field.get(holder) as String)
                }
        }

    private companion object {
        /** "2단계를 확인하세요" and friends; the flow has no numbers to point at. */
        val NUMBERED_STEP = Regex("\\d\\s*단계")
    }
}
