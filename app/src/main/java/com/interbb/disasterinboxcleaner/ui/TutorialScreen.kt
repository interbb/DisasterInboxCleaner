package com.interbb.disasterinboxcleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.interbb.disasterinboxcleaner.MonitorUiState

/**
 * The whole first-run setup: one guided screen at a time, showing only the screens this phone
 * still has something to do on. Every callback the old onboarding and guide screens owned is wired
 * here; nothing about the grant flow itself changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(
    state: MonitorUiState,
    onExit: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    grantNotificationsBlocked: () -> Boolean,
    onOpenTarget: (GuideTarget) -> Unit,
    onStartGrant: () -> Unit,
    onCancelGrant: () -> Unit,
    onSubmitConnectPort: (Int) -> Unit,
    onRequestBatteryExemption: () -> Unit,
    adbCommand: String,
    onCopyAdbCommand: () -> Unit,
    onRefresh: () -> Unit,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current

    // Developer options, wireless debugging and the notification block are Settings/NotificationManager
    // reads, not MonitorUiState fields, so nothing re-emits when the user changes them in Settings.
    // The tick makes coming back to this screen the moment they are re-read.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumeTick++
        onRefresh()
    }
    val ctx = remember(resumeTick, state) {
        TutorialContext(
            smsReadGranted = state.smsReadGranted,
            notificationAccessGranted = state.notificationAccessGranted,
            smsDeleteGranted = state.smsDeleteGranted,
            batteryExempt = state.batteryExempt,
            developerOptionsOn = GuideSteps.developerOptionsOn(context),
            wirelessDebuggingOn = GuideSteps.wirelessDebuggingOn(context),
            grantPhase = state.adbGrant.phase,
            grantMessage = state.adbGrant.message,
            notificationsBlocked = grantNotificationsBlocked(),
        )
    }

    var currentName by rememberSaveable { mutableStateOf(TutorialStep.WELCOME.name) }
    var shownNames by rememberSaveable { mutableStateOf(TutorialStep.WELCOME.name) }
    var everVisibleNames by rememberSaveable { mutableStateOf(TutorialStep.WELCOME.name) }
    var manualPortOpen by rememberSaveable { mutableStateOf(false) }
    var fallbackOpen by rememberSaveable { mutableStateOf(false) }
    var connectPortText by rememberSaveable { mutableStateOf("") }
    var zoomed by remember { mutableStateOf<TutorialPlate?>(null) }

    // Tolerates a saved name this build no longer has, the same way MainActivity's screen name does.
    val current = TutorialStep.entries.firstOrNull { it.name == currentName } ?: TutorialStep.WELCOME
    val shown = remember(shownNames) {
        shownNames.split(",").mapNotNullTo(mutableSetOf()) { name ->
            TutorialStep.entries.firstOrNull { it.name == name }
        }
    }
    val everVisible = remember(everVisibleNames) {
        everVisibleNames.split(",").mapNotNullTo(mutableSetOf()) { name ->
            TutorialStep.entries.firstOrNull { it.name == name }
        }
    }
    val face = TutorialModel.grantFace(ctx)

    fun goTo(step: TutorialStep) {
        currentName = step.name
        if (step !in shown) shownNames = "$shownNames,${step.name}"
    }

    // advanceFrom always takes the caller's own step, never the outer current, so that an action
    // bound to a fading AnimatedContent slot (see below) can only ever move on from its own screen.
    fun advanceFrom(step: TutorialStep) {
        TutorialModel.next(step, ctx, shown)?.let(::goTo)
    }

    // Grows the progress bar's denominator only at the moment the user actually arrives on a
    // screen, using the ctx as of that arrival - never live while they stand still, so a Settings
    // flag flipping mid-read cannot move the bar either way (see TutorialModel.growVisible).
    LaunchedEffect(current) {
        val grown = TutorialModel.growVisible(current, ctx, everVisible)
        if (grown != everVisible) everVisibleNames = grown.joinToString(",") { it.name }
    }

    // The only automatic move in the flow: the grant lands while the user is looking at the
    // pairing screen, so that screen hands over to whatever comes next by itself - the battery
    // exemption screen while it is still missing, otherwise straight to the finish screen.
    LaunchedEffect(ctx.smsDeleteGranted) {
        if (ctx.smsDeleteGranted && current == TutorialStep.DELETE_GRANT) advanceFrom(current)
    }

    fun goBack() {
        val previous = TutorialModel.previous(current, ctx, shown)
        if (previous == null) onExit() else goTo(previous)
    }

    BackHandler(enabled = true, onBack = ::goBack)

    zoomed?.let { plate -> ZoomDialog(plate) { zoomed = null } }

    // Bound to the AnimatedContent slot's own (step, face) below, never the outer current/face, so
    // a tap on a fading outgoing screen can only ever run that screen's own action.
    fun primaryAction(step: TutorialStep, stepFace: GrantFace) {
        val stepSatisfied = TutorialModel.isSatisfied(step, ctx)
        when (step) {
            TutorialStep.WELCOME -> advanceFrom(step)
            TutorialStep.SMS_READ -> if (stepSatisfied) advanceFrom(step) else onRequestSmsPermission()
            TutorialStep.NOTIFICATION_ACCESS -> if (stepSatisfied) advanceFrom(step) else onOpenNotificationAccess()
            TutorialStep.DEVELOPER_OPTIONS ->
                if (stepSatisfied) advanceFrom(step) else onOpenTarget(GuideTarget.DEVICE_INFO)
            TutorialStep.WIRELESS_DEBUGGING ->
                if (stepSatisfied) advanceFrom(step) else onOpenTarget(GuideTarget.DEVELOPER_OPTIONS)
            TutorialStep.DELETE_GRANT -> when (stepFace) {
                GrantFace.READY, GrantFace.FAILED -> onStartGrant()
                GrantFace.NOTIFICATIONS_BLOCKED -> onOpenAppNotificationSettings()
                GrantFace.WAITING_CODE, GrantFace.WAITING_PORT -> onOpenTarget(GuideTarget.WIRELESS_DEBUGGING)
                GrantFace.WORKING -> Unit
                GrantFace.GRANTED -> advanceFrom(step)
            }
            TutorialStep.BATTERY_EXEMPTION ->
                if (stepSatisfied) advanceFrom(step) else onRequestBatteryExemption()
            TutorialStep.DONE ->
                if (ctx.allPermissionsReady) onComplete()
                else TutorialModel.firstUnsatisfied(ctx, shown)?.let(::goTo)
        }
    }

    fun secondaryAction(step: TutorialStep, stepFace: GrantFace) {
        when (step) {
            TutorialStep.SMS_READ -> onOpenAppSettings()
            TutorialStep.DELETE_GRANT -> when (stepFace) {
                GrantFace.WAITING_CODE, GrantFace.WAITING_PORT, GrantFace.WORKING -> onCancelGrant()
                GrantFace.FAILED -> goTo(TutorialStep.WIRELESS_DEBUGGING)
                else -> Unit
            }
            else -> Unit
        }
    }

    fun primaryEnabledFor(step: TutorialStep, stepFace: GrantFace): Boolean = when (step) {
        TutorialStep.DELETE_GRANT -> stepFace != GrantFace.WORKING
        TutorialStep.DONE -> TutorialModel.donePrimaryEnabled(ctx)
        else -> true
    }

    val targetProgress = TutorialModel.progress(current, everVisible)
    val animatedProgress by animateFloatAsState(targetProgress, tween(180), label = "tutorialProgress")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    TextButton(onClick = ::goBack) { Text(TutorialCopy.BACK) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            AnimatedContent(
                targetState = current to (if (current == TutorialStep.DELETE_GRANT) face else GrantFace.READY),
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false)
                },
                modifier = Modifier.weight(1f),
                label = "tutorialStep",
            ) { (step, stepFace) ->
                TutorialPage(
                    title = TutorialCopy.title(step),
                    primaryLabel = TutorialCopy.primaryLabel(step, stepFace, ctx),
                    primaryEnabled = primaryEnabledFor(step, stepFace),
                    onPrimary = { primaryAction(step, stepFace) },
                    secondaryLabel = TutorialCopy.secondaryLabel(step, stepFace, ctx),
                    onSecondary = { secondaryAction(step, stepFace) },
                ) {
                    TutorialBody(
                        step = step,
                        face = stepFace,
                        ctx = ctx,
                        adbCommand = adbCommand,
                        onCopyAdbCommand = onCopyAdbCommand,
                        onZoom = { zoomed = it },
                        onOpenTarget = onOpenTarget,
                        onSubmitConnectPort = onSubmitConnectPort,
                        connectPortText = connectPortText,
                        onConnectPortTextChange = { connectPortText = it },
                        manualPortOpen = manualPortOpen,
                        onToggleManualPort = { manualPortOpen = !manualPortOpen },
                        fallbackOpen = fallbackOpen,
                        onToggleFallback = { fallbackOpen = !fallbackOpen },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.TutorialBody(
    step: TutorialStep,
    face: GrantFace,
    ctx: TutorialContext,
    adbCommand: String,
    onCopyAdbCommand: () -> Unit,
    onZoom: (TutorialPlate) -> Unit,
    onOpenTarget: (GuideTarget) -> Unit,
    onSubmitConnectPort: (Int) -> Unit,
    connectPortText: String,
    onConnectPortTextChange: (String) -> Unit,
    manualPortOpen: Boolean,
    onToggleManualPort: () -> Unit,
    fallbackOpen: Boolean,
    onToggleFallback: () -> Unit,
) {
    val satisfied = TutorialModel.isSatisfied(step, ctx)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (step == TutorialStep.DONE) {
            PermissionChecklist(ctx)
        }
        if (step == TutorialStep.DELETE_GRANT && face == GrantFace.FAILED) {
            Text(
                text = TutorialGrantText.failure(ctx.grantMessage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TutorialGrantText.failureDetail(ctx.grantMessage)?.let { HintText(it) }
        }
        BodyText(TutorialCopy.body(step, face, ctx))
        if (satisfied && step != TutorialStep.WELCOME && step != TutorialStep.DONE) {
            SatisfiedRow()
        }
        TutorialCopy.hint(step, face, ctx)?.let { HintText(it) }
        if (step == TutorialStep.DELETE_GRANT && face == GrantFace.WORKING) {
            TutorialGrantText.progress(ctx.grantPhase)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    if (step != TutorialStep.DONE) {
        PlateGroup(TutorialModel.plates(step, face, ctx), onZoom)
    }

    if (step == TutorialStep.DONE) {
        if (TutorialModel.showsWirelessOffNote(ctx)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BodyText(TutorialCopy.DONE_WIRELESS_OFF)
                PlateGroup(TutorialModel.plates(step, face, ctx), onZoom)
                TextButton(onClick = { onOpenTarget(GuideTarget.DEVELOPER_OPTIONS) }) {
                    Text(TutorialCopy.DONE_WIRELESS_OFF_ACTION)
                }
            }
        }
        TutorialCopy.missingSentence(ctx)?.let { BodyText(it) }
    }

    if (step == TutorialStep.DELETE_GRANT && face == GrantFace.WAITING_PORT) {
        FoldedBlock(TutorialCopy.MANUAL_PORT_BLOCK, manualPortOpen, onToggleManualPort) {
            OutlinedTextField(
                value = connectPortText,
                onValueChange = { typed -> onConnectPortTextChange(typed.filter { it.isDigit() }.take(5)) },
                label = { Text(TutorialCopy.MANUAL_PORT_FIELD) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { connectPortText.toIntOrNull()?.let(onSubmitConnectPort) },
                enabled = connectPortText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(TutorialCopy.MANUAL_PORT_APPLY) }
        }
    }

    if (step == TutorialStep.DELETE_GRANT && face != GrantFace.WORKING && face != GrantFace.GRANTED) {
        FoldedBlock(TutorialCopy.PC_BLOCK, fallbackOpen, onToggleFallback) {
            BodyText(TutorialCopy.PC_BLOCK_BODY)
            Text(
                text = adbCommand,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedButton(onClick = onCopyAdbCommand, modifier = Modifier.fillMaxWidth()) {
                Text(TutorialCopy.PC_BLOCK_COPY)
            }
        }
    }
}

/** The three permissions, each either checked off or dimmed, in the order the flow asked for them. */
@Composable
private fun PermissionChecklist(ctx: TutorialContext) {
    Column {
        PermissionLine(TutorialCopy.DONE_SMS_READ_ROW, ctx.smsReadGranted)
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        PermissionLine(TutorialCopy.DONE_NOTIFICATION_ROW, ctx.notificationAccessGranted)
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        PermissionLine(TutorialCopy.DONE_DELETE_ROW, ctx.smsDeleteGranted)
    }
}

@Composable
private fun PermissionLine(label: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (granted) {
            CheckGlyph()
        } else {
            Spacer(Modifier.size(16.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (granted) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
