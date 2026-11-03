package app.geunal.feature.calendar.alerts

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.designsystem.components.SettingsDivider
import app.geunal.designsystem.components.SettingsGroup
import app.geunal.designsystem.components.SettingsRow
import app.geunal.designsystem.components.StatusBadge
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.editor.AlertText
import app.geunal.feature.calendar.editor.text
import app.geunal.feature.calendar.support.DIMMED
import app.geunal.feature.calendar.support.MenuRow
import app.geunal.feature.calendar.support.SwitchRow
import app.geunal.designsystem.R as DesignR

/**
 * 알림 설정 (m1.md screen 19, boards/NotifySettings), over the tabs: the default alerts of timed and all-day events and
 * the next-up bar, birthday alerts and the long-weekend suggestions, the groups' alerts (M2), and the system's own
 * settings. With notifications off in the system, a card says so and the switches are dimmed until they are back on.
 */
@Composable
fun AlertSettingsScreen(
    model: AlertSettingsModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    val context = LocalContext.current
    LaunchedEffect(model) { model.start() }
    LifecycleResumeEffect(model) {
        model.resumed()
        onPauseOrDispose {}
    }
    Column(
        modifier
            .fillMaxSize()
            .background(neutrals.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .heightIn(min = Metrics.minHitTarget)
                        .clip(RoundedCornerShape(Radius.cell))
                        .clickable(role = Role.Button, onClick = onClose)
                        .padding(end = Space.xs)
                        .testTag("alerts.back"),
            ) {
                Icon(painterResource(DesignR.drawable.ds_ic_chevron_left), contentDescription = null, tint = neutrals.ink)
                Text(stringResource(R.string.me_back), style = GeunalType.body, color = neutrals.ink)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.m),
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.l)
                    .padding(bottom = Space.l),
        ) {
            Text(
                stringResource(R.string.alerts_title),
                style = GeunalType.largeTitle,
                color = neutrals.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.alerts_note), style = GeunalType.secondary, color = neutrals.secondaryText)
            if (!state.systemEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.testTag("alerts.systemOff")) {
                    NoticeCard(stringResource(R.string.alerts_system_off_card), kind = NoticeKind.Offline)
                    GeunalButton(
                        text = stringResource(R.string.alerts_turn_on),
                        onClick = { openSystemSettings(context) },
                        kind = ButtonKind.Secondary,
                        modifier = Modifier.testTag("alerts.turnOn"),
                    )
                }
            }
            if (state.failed) NoticeCard(stringResource(R.string.alerts_failed), kind = NoticeKind.Problem)
            state.settings?.let { settings ->
                val enabled = state.systemEnabled
                Section(stringResource(R.string.alerts_events))
                SettingsGroup {
                    MenuRow(
                        title = stringResource(R.string.alerts_event_default),
                        value = minutesText(settings.eventAlert, allDay = false),
                        choices = AlertSettingsModel.EVENT_CHOICES,
                        label = { minutesText(it, allDay = false) },
                        onPick = model::setEventAlert,
                        enabled = enabled,
                        modifier = Modifier.testTag("alerts.event"),
                    )
                    SettingsDivider()
                    MenuRow(
                        title = stringResource(R.string.alerts_all_day),
                        value = minutesText(settings.allDayAlert, allDay = true),
                        choices = AlertSettingsModel.ALL_DAY_CHOICES,
                        label = { minutesText(it, allDay = true) },
                        onPick = model::setAllDayAlert,
                        enabled = enabled,
                        modifier = Modifier.testTag("alerts.allDay"),
                    )
                    SettingsDivider()
                    SwitchRow(
                        title = stringResource(R.string.alerts_next_up),
                        note = stringResource(R.string.alerts_next_up_note),
                        checked = settings.nextUpBar,
                        onChange = model::setNextUpBar,
                        modifier = Modifier.testTag("alerts.nextUp"),
                    )
                }
                Footnote(stringResource(R.string.alerts_events_note))
                Section(stringResource(R.string.alerts_people))
                SettingsGroup {
                    SwitchRow(
                        title = stringResource(R.string.alerts_birthdays),
                        note = stringResource(R.string.alerts_birthdays_time),
                        checked = settings.birthdayAlerts,
                        onChange = model::setBirthdayAlerts,
                        enabled = enabled,
                        modifier = Modifier.testTag("alerts.birthdays"),
                    )
                    SettingsDivider()
                    SwitchRow(
                        title = stringResource(R.string.alerts_leave),
                        note = stringResource(R.string.alerts_leave_note),
                        checked = settings.leaveSuggestions,
                        onChange = model::setLeaveSuggestions,
                        enabled = enabled,
                        modifier = Modifier.testTag("alerts.leave"),
                    )
                }
                Section(stringResource(R.string.alerts_groups))
                SettingsGroup {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Metrics.Row.twoLine)
                                .alpha(DIMMED)
                                .padding(horizontal = Space.m, vertical = Space.xs),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.alerts_groups_chat), style = GeunalType.body, color = neutrals.ink)
                            Text(stringResource(R.string.alerts_groups_note), style = GeunalType.caption, color = neutrals.secondaryText)
                        }
                        Spacer(Modifier.width(Space.xs))
                        StatusBadge(stringResource(R.string.alerts_m2))
                    }
                }
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.alerts_system),
                        value =
                            stringResource(if (state.systemEnabled) R.string.alerts_system_on else R.string.alerts_system_off),
                        onClick = { openSystemSettings(context) },
                        modifier = Modifier.testTag("alerts.system"),
                    )
                }
                Footnote(stringResource(R.string.alerts_system_note))
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
        color = LocalNeutralColors.current.secondaryText,
        modifier = Modifier.padding(start = Space.m, top = Space.xs).semantics { heading() },
    )
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = GeunalType.caption,
        color = LocalNeutralColors.current.secondaryText,
        modifier = Modifier.padding(horizontal = Space.m),
    )
}

/** "10분 전", "전날 18:00", or "없음" for none. */
@Composable
private fun minutesText(
    minutes: Int?,
    allDay: Boolean,
): String {
    val resources = LocalResources.current
    return minutes?.let { AlertText.of(it, allDay).text(resources) } ?: stringResource(R.string.editor_alert_off)
}

/** The system's notification settings for this app: sound, the lock screen and summaries are kept there. */
private fun openSystemSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
