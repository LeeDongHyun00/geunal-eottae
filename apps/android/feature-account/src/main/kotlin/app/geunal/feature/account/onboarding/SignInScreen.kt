package app.geunal.feature.account.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.Provider
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.CheckboxRow
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.pressScale
import app.geunal.feature.account.R
import app.geunal.designsystem.R as DesignR

/** Links under the buttons; empty until the domain exists, and then the words show without a link. */
data class LegalLinks(
    val terms: String = "",
    val privacy: String = "",
)

/**
 * Screen 2. The age box turns the Kakao and Apple buttons on; their colors follow the providers' rules, so off they
 * are dimmed to 0.4, the one exception to "꺼짐은 fill/tertiaryText" (m1.md decision 23). "복구 코드로 돌아오기" is a
 * way back to an existing account and stays on whatever the box says.
 */
@Composable
fun SignInScreen(
    model: SignInModel,
    isOnline: Boolean,
    legal: LegalLinks,
    onBack: () -> Unit,
    onSignIn: (Provider) -> Unit,
    onGuest: () -> Unit,
    onGuestReturn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    val providersOn = state.isAgeConfirmed && state.busy == null && isOnline
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(neutrals.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Metrics.Button.sideInset),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(top = Space.xs).size(Metrics.minHitTarget)) {
            Icon(
                painterResource(DesignR.drawable.ds_ic_chevron_left),
                contentDescription = stringResource(R.string.sign_in_back),
                tint = neutrals.ink,
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(Space.xl))
            Text(stringResource(R.string.sign_in_title), style = GeunalType.largeTitle, color = neutrals.ink)
            Spacer(Modifier.height(Space.s))
            Text(stringResource(R.string.sign_in_description), style = GeunalType.secondary, color = neutrals.secondaryText)
        }
        CheckboxRow(
            checked = state.isAgeConfirmed,
            onCheckedChange = model::setAgeConfirmed,
            title = stringResource(R.string.sign_in_age_title),
            detail = stringResource(R.string.sign_in_age_detail),
            modifier = Modifier.testTag("signIn.age"),
        )
        Spacer(Modifier.height(Space.s))
        BrandButton(
            text =
                if (state.busy ==
                    SignInModel.Busy.KAKAO
                ) {
                    stringResource(R.string.sign_in_busy)
                } else {
                    stringResource(R.string.sign_in_kakao)
                },
            container = KakaoYellow,
            content = KakaoLabel,
            icon = R.drawable.account_ic_kakao,
            enabled = providersOn,
            onClick = { onSignIn(Provider.KAKAO) },
            modifier = Modifier.testTag("signIn.kakao"),
        )
        Spacer(Modifier.height(Space.s))
        BrandButton(
            text =
                if (state.busy ==
                    SignInModel.Busy.APPLE
                ) {
                    stringResource(R.string.sign_in_busy)
                } else {
                    stringResource(R.string.sign_in_apple)
                },
            container = Color.Black,
            content = Color.White,
            icon = R.drawable.account_ic_apple,
            enabled = providersOn,
            onClick = { onSignIn(Provider.APPLE) },
            modifier = Modifier.testTag("signIn.apple"),
        )
        Spacer(Modifier.height(Space.xs))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val guestOn = state.isAgeConfirmed && state.busy == null
            GeunalButton(
                text = stringResource(R.string.sign_in_guest),
                onClick = onGuest,
                kind = ButtonKind.Text,
                enabled = guestOn,
                modifier = Modifier.testTag("signIn.guest"),
            )
            Text("·", style = GeunalType.caption, color = neutrals.tertiaryText)
            GeunalButton(
                text = stringResource(R.string.sign_in_guest_return),
                onClick = onGuestReturn,
                kind = ButtonKind.Text,
                enabled = state.busy == null,
                modifier = Modifier.testTag("signIn.guestReturn"),
            )
        }
        LegalLine(legal)
        Spacer(Modifier.height(Space.s))
    }
}

/** Kakao's login button colors (Kakao's design guide), the same in both modes. */
private val KakaoYellow = Color(0xFFFEE500)
private val KakaoLabel = Color(0xFF191919)

/** A provider's button: its own colors, 52 high like the primary button, dimmed to 0.4 when off. */
@Composable
private fun BrandButton(
    text: String,
    container: Color,
    content: Color,
    icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .pressScale(interaction)
                .heightIn(min = Metrics.Button.primary)
                .alpha(if (enabled) 1f else 0.4f)
                .clip(CircleShape)
                .background(container)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.xs))
            Text(text, style = GeunalType.button, color = content)
        }
    }
}

@Composable
private fun LegalLine(legal: LegalLinks) {
    val neutrals = LocalNeutralColors.current
    val uri = LocalUriHandler.current
    val linkStyle = TextLinkStyles(SpanStyle(color = neutrals.secondaryText, textDecoration = TextDecoration.Underline))
    val prefix = stringResource(R.string.sign_in_terms_prefix)
    val terms = stringResource(R.string.sign_in_terms)
    val and = stringResource(R.string.sign_in_terms_and)
    val privacy = stringResource(R.string.sign_in_privacy)
    val suffix = stringResource(R.string.sign_in_terms_suffix)
    val text =
        buildAnnotatedString {
            append(prefix)
            // A phone without a browser has nothing to open it with; the line stays as it is then.
            link(legal.terms, terms, linkStyle) { runCatching { uri.openUri(it) } }
            append(and)
            link(legal.privacy, privacy, linkStyle) { runCatching { uri.openUri(it) } }
            append(suffix)
        }
    Text(
        text,
        style = GeunalType.caption,
        color = neutrals.secondaryText,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
    )
}

private fun AnnotatedString.Builder.link(
    url: String,
    label: String,
    style: TextLinkStyles,
    open: (String) -> Unit,
) {
    if (url.isEmpty()) {
        append(label)
    } else {
        withLink(LinkAnnotation.Clickable(tag = url, styles = style) { open(url) }) { append(label) }
    }
}
