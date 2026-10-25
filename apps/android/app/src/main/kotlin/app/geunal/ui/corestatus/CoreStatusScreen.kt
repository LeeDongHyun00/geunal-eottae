package app.geunal.ui.corestatus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.geunal.R
import app.geunal.designsystem.LocalNeutralColors

/**
 * The M0 screen (docs/parts/android.md §8): whether the core opened, which SQLCipher it runs, how sync went, and how
 * many change notifications the listener got. Plain rows on the background, no decoration (docs/design/README.md §1).
 */
@Composable
fun CoreStatusScreen(
    status: CoreStatus,
    apiBase: String,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    Scaffold(modifier = modifier.fillMaxSize(), containerColor = neutrals.background) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.core_status_title),
                style = MaterialTheme.typography.headlineLarge,
                color = neutrals.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    if (apiBase.isEmpty()) {
                        stringResource(R.string.core_status_api_base_none)
                    } else {
                        stringResource(R.string.core_status_api_base, apiBase)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = neutrals.tertiaryText,
            )
            Spacer(Modifier.height(24.dp))
            StatusRow(label = stringResource(R.string.core_status_store), step = status.store)
            StatusRow(
                label = stringResource(R.string.core_status_cipher),
                value = status.cipherVersion ?: stringResource(R.string.core_status_unknown),
            )
            StatusRow(label = stringResource(R.string.core_status_sync), step = status.sync)
            StatusRow(
                label = stringResource(R.string.core_status_changes),
                value = stringResource(R.string.core_status_changes_count, status.changes),
            )
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    step: Step,
) {
    val value =
        when (step) {
            Step.Pending -> stringResource(R.string.core_status_pending)
            Step.Done -> stringResource(R.string.core_status_done)
            is Step.Failed -> stringResource(R.string.core_status_failed)
        }
    StatusRow(label = label, value = value, detail = (step as? Step.Failed)?.reason)
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    detail: String? = null,
) {
    val neutrals = LocalNeutralColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = neutrals.ink)
            Text(text = value, style = MaterialTheme.typography.bodyLarge, color = neutrals.secondaryText)
        }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = neutrals.sunday,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        HorizontalDivider(color = neutrals.separator)
    }
}
