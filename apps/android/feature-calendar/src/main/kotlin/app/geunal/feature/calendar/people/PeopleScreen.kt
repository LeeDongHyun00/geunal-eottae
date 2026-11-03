package app.geunal.feature.calendar.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.Person
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.StatusBadge
import app.geunal.designsystem.components.ToastCenter
import app.geunal.designsystem.components.ToastHost
import app.geunal.feature.calendar.R
import app.geunal.designsystem.R as DesignR

/**
 * 사람·생일 (m1.md screen 11, boards/People), over the tabs: ‹ 나 · ＋, the title, the search, the coming birthdays,
 * everyone, and the import from contacts. A row opens the person sheet (screen 17); how an import went shows as a
 * toast here, since the tabs' toast slot is under this screen.
 */
@Composable
fun PeopleScreen(
    model: PeopleModel,
    toasts: ToastCenter,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val editing by model.editing.collectAsStateWithLifecycle()
    val importing by model.importing.collectAsStateWithLifecycle()
    val imported by model.imported.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    LaunchedEffect(model) { model.start() }
    LifecycleResumeEffect(model) {
        model.resumed()
        onPauseOrDispose {}
    }
    LaunchedEffect(imported) {
        imported?.let {
            toasts.show(PeopleText.imported(it, resources))
            model.importShown()
        }
    }
    Box(modifier.fillMaxSize().background(neutrals.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
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
                            .testTag("people.back"),
                ) {
                    Icon(painterResource(DesignR.drawable.ds_ic_chevron_left), contentDescription = null, tint = neutrals.ink)
                    Text(stringResource(R.string.people_back), style = GeunalType.body, color = neutrals.ink)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { model.edit(null) }, modifier = Modifier.testTag("people.add")) {
                    Icon(
                        painterResource(DesignR.drawable.ds_ic_add),
                        contentDescription = stringResource(R.string.people_add),
                        tint = neutrals.ink,
                    )
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
                    stringResource(R.string.people_title),
                    style = GeunalType.largeTitle,
                    color = neutrals.ink,
                    modifier = Modifier.semantics { heading() },
                )
                if (state.hasLoaded && state.people.isEmpty()) {
                    EmptyPeople(onAdd = { model.edit(null) }, onContacts = model::openImport)
                } else if (state.hasLoaded) {
                    SearchField(state.query, model::setQuery)
                    People(state, onPerson = model::edit)
                    GeunalButton(
                        text = stringResource(R.string.people_import),
                        onClick = model::openImport,
                        kind = ButtonKind.Secondary,
                        leadingIcon = painterResource(DesignR.drawable.ds_ic_contact),
                        modifier = Modifier.testTag("people.import"),
                    )
                    Text(
                        stringResource(R.string.people_import_note),
                        style = GeunalType.caption,
                        color = neutrals.secondaryText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        ToastHost(
            center = toasts,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = Space.m)
                    .padding(bottom = Space.xl),
        )
    }
    editing?.let { person ->
        PersonEditSheet(model = person, onImportContacts = model::openImport, onDismiss = model::closeEditor)
    }
    importing?.let { import ->
        ContactsImportSheet(model = import, onAddByHand = model::addByHand, onDismiss = model::closeImport)
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val placeholder = stringResource(R.string.people_search)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Metrics.minHitTarget)
                .clip(CircleShape)
                .background(neutrals.fill)
                .padding(horizontal = Space.m),
    ) {
        Icon(
            painterResource(DesignR.drawable.ds_ic_search),
            contentDescription = null,
            tint = neutrals.secondaryText,
            modifier = Modifier.size(20.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = GeunalType.body.copy(color = neutrals.ink),
            cursorBrush = SolidColor(neutrals.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { contentDescription = placeholder }
                    .testTag("people.search"),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(placeholder, style = GeunalType.body, color = neutrals.tertiaryText)
                    field()
                }
            },
        )
    }
}

@Composable
private fun People(
    state: PeopleModel.State,
    onPerson: (Person) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    if (state.shownUpcoming.isNotEmpty()) {
        SectionTitle(stringResource(R.string.people_upcoming))
        Column {
            state.shownUpcoming.forEach { person ->
                PersonRow(
                    person = person,
                    title = person.name,
                    detail = PeopleText.upcoming(person, state.today, resources),
                    onClick = { onPerson(person) },
                    trailing = {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                            person.daysUntil?.let { StatusBadge(PeopleText.dDay(it.toInt(), resources)) }
                            Text(PeopleText.source(person.source, resources), style = GeunalType.caption, color = neutrals.secondaryText)
                        }
                    },
                    tag = "people.upcoming.${person.id}",
                )
            }
        }
    }
    SectionTitle(stringResource(R.string.people_all, state.people.size))
    if (state.shownPeople.isEmpty()) {
        Text(stringResource(R.string.people_no_match), style = GeunalType.secondary, color = neutrals.secondaryText)
    }
    Column {
        state.shownPeople.forEach { person ->
            PersonRow(
                person = person,
                title = person.name,
                detail = null,
                onClick = { onPerson(person) },
                trailing = {
                    Text(
                        PeopleText.birthday(person.birthday, resources),
                        style = GeunalType.body,
                        color = neutrals.secondaryText,
                    )
                },
                tag = "people.person.${person.id}",
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
        color = LocalNeutralColors.current.secondaryText,
        modifier = Modifier.padding(top = Space.xs).semantics { heading() },
    )
}

/** A person: the first letter in a circle, the name (and a line under it), and what the row ends with. */
@Composable
private fun PersonRow(
    person: Person,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    tag: String,
) {
    val neutrals = LocalNeutralColors.current
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (detail != null) Metrics.Row.card - 20.dp else Metrics.Row.setting)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(vertical = Space.xs)
                    .testTag(tag),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(if (detail != null) 40.dp else 32.dp).background(neutrals.fill, CircleShape),
            ) {
                Text(person.name.take(1), style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold), color = neutrals.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = if (detail != null) GeunalType.bodyStrong else GeunalType.body, color = neutrals.ink, maxLines = 1)
                if (detail != null) Text(detail, style = GeunalType.caption, color = neutrals.secondaryText)
            }
            trailing()
        }
        HorizontalDivider(thickness = 1.dp, color = neutrals.fill)
    }
}

@Composable
private fun EmptyPeople(
    onAdd: () -> Unit,
    onContacts: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xxxl),
    ) {
        Text(stringResource(R.string.people_empty), style = GeunalType.bodyStrong, color = neutrals.ink)
        Text(
            stringResource(R.string.people_empty_body),
            style = GeunalType.secondary,
            color = neutrals.secondaryText,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            GeunalButton(
                text = stringResource(R.string.people_add_by_hand),
                onClick = onAdd,
                kind = ButtonKind.Secondary,
                height = Metrics.Button.secondary,
                fullWidth = false,
                modifier = Modifier.testTag("people.empty.add"),
            )
            GeunalButton(
                text = stringResource(R.string.people_empty_contacts),
                onClick = onContacts,
                kind = ButtonKind.Secondary,
                height = Metrics.Button.secondary,
                fullWidth = false,
                modifier = Modifier.testTag("people.empty.contacts"),
            )
        }
        Text(
            stringResource(R.string.people_import_note),
            style = GeunalType.caption,
            color = neutrals.secondaryText,
            textAlign = TextAlign.Center,
        )
    }
}
