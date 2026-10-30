package app.geunal.feature.account.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.feature.account.R
import app.geunal.designsystem.R as DesignR

/**
 * Screen 3. The system dialogs open from here, after the card has said what the permission is for and where it stops
 * (tone.md "권한 요청"); the model only learns the answers.
 */
@Composable
fun PermissionsScreen(
    model: PermissionsModel,
    permissions: SystemPermissions,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    // The first screen once the account is set (m1.md §2.1 "뒤로 가기"): back means "나중에 할게요", never the sign-in.
    BackHandler(onBack = onFinish)
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), model::calendarAnswered)
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), model::notificationsAnswered)
    // Coming back from the settings app, or after a system dialog: read what the OS says now.
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose { }
    }

    fun perform(
        action: PermissionAction,
        launch: (String) -> Unit,
    ) {
        when (action) {
            is PermissionAction.Ask -> launch(action.permission)
            PermissionAction.OpenAppSettings -> permissions.openAppSettings()
            PermissionAction.OpenNotificationSettings -> permissions.openNotificationSettings()
            PermissionAction.None -> Unit
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(neutrals.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Metrics.Button.sideInset),
    ) {
        // Where the back button stood before the account was set, so the title stays where the board has it.
        Spacer(Modifier.padding(top = Space.xs).height(Metrics.minHitTarget))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Spacer(Modifier.height(Space.l))
            Text(
                stringResource(if (state.showsAlphaStats) R.string.permissions_title_three else R.string.permissions_title_two),
                style = GeunalType.largeTitle,
                color = neutrals.ink,
            )
            Text(stringResource(R.string.permissions_description), style = GeunalType.secondary, color = neutrals.secondaryText)
            Spacer(Modifier.height(Space.xs))
            PermissionCard(
                icon = DesignR.drawable.ds_ic_calendar,
                title = stringResource(R.string.permissions_calendar_title),
                body = stringResource(R.string.permissions_calendar_body),
                button =
                    when (state.calendar) {
                        PermissionState.NOT_ASKED -> stringResource(R.string.permissions_calendar_ask)
                        PermissionState.GRANTED -> stringResource(R.string.permissions_calendar_done, state.calendarCount)
                        PermissionState.DENIED -> stringResource(R.string.permissions_settings)
                    },
                pending = state.calendar == PermissionState.NOT_ASKED,
                onClick = { perform(model.calendarTapped()) { calendarLauncher.launch(it) } },
                tag = "permissions.calendar",
            )
            PermissionCard(
                icon = DesignR.drawable.ds_ic_bell,
                title = stringResource(R.string.permissions_notifications_title),
                body = stringResource(R.string.permissions_notifications_body),
                button =
                    when (state.notifications) {
                        PermissionState.NOT_ASKED -> stringResource(R.string.permissions_notifications_ask)
                        PermissionState.GRANTED -> stringResource(R.string.permissions_notifications_done)
                        PermissionState.DENIED -> stringResource(R.string.permissions_settings)
                    },
                pending = state.notifications == PermissionState.NOT_ASKED,
                onClick = { perform(model.notificationsTapped()) { notificationLauncher.launch(it) } },
                tag = "permissions.notifications",
            )
            if (state.showsAlphaStats) {
                PermissionCard(
                    icon = DesignR.drawable.ds_ic_chart,
                    title = stringResource(R.string.permissions_stats_title),
                    badge = stringResource(R.string.permissions_stats_badge),
                    body = stringResource(R.string.permissions_stats_body),
                    button =
                        if (state.alphaStats) {
                            stringResource(R.string.permissions_stats_done)
                        } else {
                            stringResource(R.string.permissions_stats_ask)
                        },
                    pending = !state.alphaStats,
                    onClick = model::alphaStatsTapped,
                    tag = "permissions.stats",
                )
            }
            Spacer(Modifier.height(Space.s))
        }
        GeunalButton(
            text = stringResource(R.string.permissions_open_calendar),
            onClick = onFinish,
            enabled = state.canOpenCalendar,
            modifier = Modifier.testTag("permissions.open"),
        )
        GeunalButton(
            text = stringResource(R.string.permissions_later),
            onClick = onFinish,
            kind = ButtonKind.Text,
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs).testTag("permissions.later"),
        )
    }
}

@Composable
private fun PermissionCard(
    icon: Int,
    title: String,
    body: String,
    button: String,
    pending: Boolean,
    onClick: () -> Unit,
    tag: String,
    badge: String? = null,
) {
    val neutrals = LocalNeutralColors.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card + 8.dp))
                .background(neutrals.groupedBackground)
                .padding(Space.m),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(Metrics.minHitTarget)
                        .clip(RoundedCornerShape(Radius.input))
                        .background(neutrals.background),
            ) {
                Icon(painterResource(icon), contentDescription = null, tint = neutrals.ink, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(title, style = GeunalType.bodyStrong, color = neutrals.ink)
                    if (badge != null) {
                        Spacer(Modifier.width(Space.xxs))
                        Text(badge, style = GeunalType.micro, color = neutrals.secondaryText)
                    }
                }
                Spacer(Modifier.height(Space.xxs))
                Text(body, style = GeunalType.secondary, color = neutrals.secondaryText)
            }
        }
        Spacer(Modifier.height(Space.s))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            // To ask: the ink pill. Answered or refused: the plain pill that says what happened, or how to change it.
            GeunalButton(
                text = button,
                onClick = onClick,
                kind = if (pending) ButtonKind.Primary else ButtonKind.Surface,
                height = 40.dp,
                fullWidth = false,
                modifier = Modifier.testTag(tag),
            )
        }
    }
}
