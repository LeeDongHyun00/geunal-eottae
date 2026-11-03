package app.geunal.ui.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.geunal.R
import app.geunal.designsystem.BrandColors
import app.geunal.designsystem.GroupColors
import app.geunal.designsystem.LocalDarkTheme
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Motion
import app.geunal.designsystem.components.ProposalHost
import app.geunal.designsystem.components.ToastCenter
import app.geunal.designsystem.components.ToastHost
import app.geunal.designsystem.components.ToastStyle
import app.geunal.feature.calendar.nextup.NextUpBar
import app.geunal.feature.calendar.nextup.NextUpModel
import app.geunal.designsystem.R as DesignR

/** The tabs of M1 (m1.md decision 1): 그룹 and 채팅 join at M2. */
enum class MainTab { CALENDAR, ME }

/**
 * The tab bar with the next-up bar floating 8 above it (docs/design/README.md §5). Android draws the Material 3
 * navigation bar in the brand colors; a toast takes the next-up bar's place while it shows, as the bar's capsule, and
 * a proposal card floats 8 above that (m1.md screen 6, decision 27).
 */
@Composable
fun MainScaffold(
    nextUp: NextUpModel,
    toasts: ToastCenter,
    modifier: Modifier = Modifier,
    content: @Composable (tab: MainTab, padding: PaddingValues) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.CALENDAR) }
    val toast by toasts.current.collectAsState()
    val neutrals = LocalNeutralColors.current
    // The bars' height, so the card floats over the screen above them instead of pushing it up.
    var barsHeight by remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = neutrals.background,
            bottomBar = {
                Column(Modifier.onSizeChanged { barsHeight = it.height }) {
                    // The event editor opens from the bar once it exists (m1.md §2.6); until then it leads to the calendar.
                    NextUpSlot(nextUp = nextUp, toasts = toasts, toastShown = toast != null, onOpen = { tab = MainTab.CALENDAR })
                    TabBar(tab = tab, onSelect = { tab = it })
                }
            },
        ) { padding ->
            content(tab, padding)
        }
        ProposalHost(
            center = toasts,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = Metrics.TabBar.sideInset)
                    .padding(bottom = with(LocalDensity.current) { barsHeight.toDp() } + Metrics.Toast.gapAboveTabBar),
        )
    }
}

/** The next-up bar, or the toast in its place while one shows. */
@Composable
private fun NextUpSlot(
    nextUp: NextUpModel,
    toasts: ToastCenter,
    toastShown: Boolean,
    onOpen: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.TabBar.sideInset)
            .padding(bottom = Metrics.NextUpBar.gapAboveTabBar),
    ) {
        AnimatedVisibility(
            visible = !toastShown,
            enter = fadeIn(tween(Motion.TOAST_OUT_MS)),
            exit = fadeOut(tween(Motion.TOAST_IN_MS)),
        ) {
            NextUpBar(model = nextUp, onOpen = { onOpen() })
        }
        ToastHost(center = toasts, style = ToastStyle.Slot)
    }
}

@Composable
private fun TabBar(
    tab: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val dark = LocalDarkTheme.current
    // README §5: in the dark the bar takes the grouped background and the pill grape's dark tint, and the other tabs the
    // tertiary text, as the secondary one is lighter than the selected grape and would stand out more than it.
    val grape = GroupColors.first { it.name == "grape" }
    val selected = if (dark) BrandColors.tabTextDark else BrandColors.tabTextLight
    val unselected = if (dark) neutrals.tertiaryText else neutrals.secondaryText
    val colors =
        NavigationBarItemDefaults.colors(
            selectedIconColor = selected,
            selectedTextColor = selected,
            indicatorColor = if (dark) grape.dark[1] else BrandColors.androidIndicator,
            unselectedIconColor = unselected,
            unselectedTextColor = unselected,
        )
    NavigationBar(containerColor = if (dark) neutrals.groupedBackground else BrandColors.androidBar) {
        TabItem(MainTab.CALENDAR, tab, R.string.tab_calendar, DesignR.drawable.ds_ic_calendar, colors, onSelect)
        TabItem(MainTab.ME, tab, R.string.tab_me, DesignR.drawable.ds_ic_person, colors, onSelect)
    }
}

@Composable
private fun RowScope.TabItem(
    item: MainTab,
    current: MainTab,
    label: Int,
    icon: Int,
    colors: NavigationBarItemColors,
    onSelect: (MainTab) -> Unit,
) {
    NavigationBarItem(
        selected = item == current,
        onClick = { onSelect(item) },
        icon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(Metrics.TabBar.icon)) },
        label = { Text(stringResource(label)) },
        colors = colors,
        modifier = Modifier.testTag("tab.${item.name.lowercase()}"),
    )
}
