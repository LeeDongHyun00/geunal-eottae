package app.geunal.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.geunal.R
import app.geunal.core.AccountView
import app.geunal.core.CoreInterface
import app.geunal.core.Provider
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.GroupColors
import app.geunal.designsystem.LocalDarkTheme
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The 나 tab's account card. The rest of the screen (m1.md screen 12: calendars, recovery code, backup, deletion)
 * follows with those screens.
 */
@Composable
fun MeScreen(
    core: CoreInterface,
    pendingGuest: Boolean,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    var account by remember { mutableStateOf<AccountView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(core) {
        account = withContext(Dispatchers.IO) { runCatching { core.account() }.getOrNull() }
        loaded = true
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.l),
        modifier =
            modifier
                .fillMaxSize()
                .background(neutrals.groupedBackground)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.m),
    ) {
        Text(
            stringResource(R.string.me_title),
            style = GeunalType.largeTitle,
            color = neutrals.ink,
            modifier = Modifier.padding(top = Space.xl).semantics { heading() },
        )
        if (loaded) Profile(account, pendingGuest)
    }
}

@Composable
private fun Profile(
    account: AccountView?,
    pendingGuest: Boolean,
) {
    val neutrals = LocalNeutralColors.current
    val dark = LocalDarkTheme.current
    val grape = GroupColors.first { it.name == "grape" }
    val (name, kind) =
        when {
            account != null ->
                account.displayName to
                    stringResource(
                        when {
                            Provider.KAKAO in account.providers -> R.string.me_kind_kakao
                            Provider.APPLE in account.providers -> R.string.me_kind_apple
                            else -> R.string.me_kind_guest
                        },
                    )
            // A guest who started offline: the account is made on the first connection (m1.md decision 16).
            pendingGuest -> stringResource(R.string.me_guest_name) to stringResource(R.string.me_kind_pending_guest)
            else -> stringResource(R.string.me_no_account) to stringResource(R.string.me_no_account_detail)
        }
    val card = stringResource(R.string.me_profile_description, name, kind)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(neutrals.background, RoundedCornerShape(Radius.card))
                .padding(Space.m)
                // Ahead of clearAndSetSemantics, which drops whatever comes after it in the chain, a tag included.
                .testTag("me.profile")
                .clearAndSetSemantics { contentDescription = card },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(Space.xxxl)
                    .background(if (dark) grape.dark[1] else grape.light[1], CircleShape),
        ) {
            Text(name.take(1), style = GeunalType.title2, color = neutrals.ink)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = GeunalType.bodyStrong, color = neutrals.ink)
            Text(kind, style = GeunalType.caption, color = neutrals.secondaryText)
        }
    }
}
