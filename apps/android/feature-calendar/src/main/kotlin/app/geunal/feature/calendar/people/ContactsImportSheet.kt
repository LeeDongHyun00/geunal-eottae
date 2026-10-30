package app.geunal.feature.calendar.people

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.GeunalSheet
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.feature.calendar.R
import app.geunal.designsystem.R as DesignR

/**
 * "연락처에서 생일 가져오기" (m1.md §2.4): the system's question first, then the contacts with a birthday, each with a
 * checkbox and none chosen, and "N명 가져오기". Turned down, the sheet says where to turn it on and offers to add by
 * hand; coming back from the settings with it on reads the contacts at once.
 */
@Composable
fun ContactsImportSheet(
    model: ContactsImportModel,
    onAddByHand: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), model::answered)
    LaunchedEffect(model) { model.start { launcher.launch(Manifest.permission.READ_CONTACTS) } }
    LifecycleResumeEffect(model) {
        model.resumed()
        onPauseOrDispose {}
    }
    GeunalSheet(onDismiss = onDismiss, dismissible = state.phase != ContactsImportModel.Phase.IMPORTING) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.m),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Metrics.Sheet.paddingSide)
                    .padding(top = Space.s, bottom = Metrics.Sheet.paddingBottom),
        ) {
            Header(onDismiss)
            when (state.phase) {
                ContactsImportModel.Phase.CHECKING ->
                    Box(Modifier.fillMaxWidth().padding(vertical = Space.xl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LocalNeutralColors.current.secondaryText, modifier = Modifier.size(28.dp))
                    }
                ContactsImportModel.Phase.DENIED -> Denied(onAddByHand)
                ContactsImportModel.Phase.PICKING, ContactsImportModel.Phase.IMPORTING ->
                    if (state.candidates.isEmpty()) Nobody(onAddByHand) else Picking(state, model)
            }
        }
    }
}

@Composable
private fun Header(onDismiss: () -> Unit) {
    val neutrals = LocalNeutralColors.current
    Row(verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs), modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.people_import),
                style = GeunalType.title2,
                color = neutrals.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.people_import_note), style = GeunalType.caption, color = neutrals.secondaryText)
        }
        IconButton(onClick = onDismiss) {
            Icon(
                painterResource(DesignR.drawable.ds_ic_close),
                contentDescription = stringResource(R.string.contacts_close),
                tint = neutrals.secondaryText,
            )
        }
    }
}

@Composable
private fun Denied(onAddByHand: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        NoticeCard(stringResource(R.string.contacts_denied), kind = NoticeKind.Offline)
        GeunalButton(
            text = stringResource(R.string.contacts_settings),
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            modifier = Modifier.testTag("contacts.settings"),
        )
        GeunalButton(
            text = stringResource(R.string.people_add_by_hand),
            onClick = onAddByHand,
            kind = ButtonKind.Text,
            fullWidth = true,
            modifier = Modifier.testTag("contacts.addByHand"),
        )
    }
}

@Composable
private fun Nobody(onAddByHand: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        NoticeCard(stringResource(R.string.contacts_none), kind = NoticeKind.Offline)
        GeunalButton(
            text = stringResource(R.string.people_add_by_hand),
            onClick = onAddByHand,
            modifier = Modifier.testTag("contacts.addByHand"),
        )
    }
}

@Composable
private fun ColumnScope.Picking(
    state: ContactsImportModel.State,
    model: ContactsImportModel,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val picking = state.phase == ContactsImportModel.Phase.PICKING
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.contacts_count, state.candidates.size),
            style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
            color = neutrals.secondaryText,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        GeunalButton(
            text = stringResource(if (state.allSelected) R.string.contacts_choose_none else R.string.contacts_choose_all),
            onClick = model::toggleAll,
            kind = ButtonKind.SmallPill,
            enabled = picking,
            modifier = Modifier.testTag("contacts.all"),
        )
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        state.candidates.forEach { contact ->
            val on = contact.contactId in state.selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Metrics.Row.setting)
                        .toggleable(value = on, enabled = picking, role = Role.Checkbox) { model.toggle(contact.contactId) }
                        .testTag("contacts.${contact.contactId}"),
            ) {
                Checkbox(
                    checked = on,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = neutrals.ink, checkmarkColor = neutrals.background),
                )
                Text(contact.name, style = GeunalType.body, color = neutrals.ink, modifier = Modifier.weight(1f))
                Text(PeopleText.birthday(contact.birthday, resources), style = GeunalType.body, color = neutrals.secondaryText)
            }
        }
    }
    if (state.withoutBirthday > 0) {
        Text(
            stringResource(R.string.contacts_without_birthday, state.withoutBirthday),
            style = GeunalType.caption,
            color = neutrals.secondaryText,
        )
    }
    if (state.failed) NoticeCard(stringResource(R.string.contacts_failed), kind = NoticeKind.Problem)
    GeunalButton(
        text =
            if (state.selected.isEmpty()) {
                stringResource(R.string.contacts_choose_first)
            } else {
                stringResource(R.string.contacts_import, state.selected.size)
            },
        onClick = model::launchImport,
        enabled = picking && state.selected.isNotEmpty(),
        modifier = Modifier.padding(top = Space.xxs).testTag("contacts.import"),
    )
}
