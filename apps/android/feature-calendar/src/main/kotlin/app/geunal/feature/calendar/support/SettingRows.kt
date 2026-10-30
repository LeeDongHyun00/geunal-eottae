package app.geunal.feature.calendar.support

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.GeunalSwitch
import app.geunal.designsystem.R as DesignR

/** A setting that is on or off: the title, a line under it if any, and the switch. The whole row toggles it. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    enabled: Boolean = true,
) {
    val neutrals = LocalNeutralColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = if (note != null) Metrics.Row.twoLine else Metrics.Row.setting)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .alpha(if (enabled) 1f else DIMMED)
                .padding(horizontal = Space.m, vertical = Space.xs),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = GeunalType.body, color = neutrals.ink)
            if (note != null) Text(note, style = GeunalType.caption, color = neutrals.secondaryText)
        }
        GeunalSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A setting picked from a short list: the title, the value and a chevron, and the list as a menu under the row. */
@Composable
fun <T> MenuRow(
    title: String,
    value: String,
    choices: List<T>,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val neutrals = LocalNeutralColors.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                modifier
                    .fillMaxWidth()
                    .heightIn(min = Metrics.Row.setting)
                    .clickable(enabled = enabled, role = Role.Button) { open = true }
                    .alpha(if (enabled) 1f else DIMMED)
                    .padding(horizontal = Space.m),
        ) {
            Text(title, style = GeunalType.body, color = neutrals.ink, modifier = Modifier.weight(1f))
            Text(value, style = GeunalType.body, color = neutrals.secondaryText)
            Spacer(Modifier.width(Space.xxs))
            Icon(
                painterResource(DesignR.drawable.ds_ic_chevron_right),
                contentDescription = null,
                tint = neutrals.tertiaryText,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(label(choice), style = GeunalType.body) },
                    onClick = {
                        open = false
                        onPick(choice)
                    },
                )
            }
        }
    }
}

/** How a row that cannot be used now looks (m1.md screens 15 and 19: "흐림 0.4"). */
const val DIMMED = 0.4f
