package app.geunal.feature.account

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.core.Revocation
import app.geunal.core.RevokedVia
import app.geunal.designsystem.GeunalTheme
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.feature.account.signedout.RevokedSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** 기기 로그아웃됨 (m1.md screen 23). */
@RunWith(AndroidJUnit4::class)
class RevokedSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Set on the main thread by the sheet, read by the test thread.
    @Volatile private var signIn = false

    private fun show(via: RevokedVia) {
        val revocation = Revocation(Instant.parse("2026-10-25T09:20:00Z"), "ios", via)
        compose.setContent {
            GeunalTheme {
                // The tabs the sheet covers in the app. Without them the activity's window is empty, and its capture
                // (the picture the sheet is laid over) fails on a zero-size root.
                Box(Modifier.fillMaxSize().background(LocalNeutralColors.current.background))
                RevokedSheet(revocation, onSignIn = { signIn = true }, onCalendarOnly = {})
            }
        }
    }

    @Test
    fun aProviderSignInSaysWhereAndLeadsBackToSigningIn() {
        show(RevokedVia.PROVIDER)
        compose.waitForText("다른 기기에서 로그인했어요")
        compose.waitForText("그 계정의 비밀번호를 바꿔 주세요", substring = true)
        compose.screenshot("23-revoked-provider", sheet = true)

        compose.onNodeWithTag("revoked.signIn").performScrollTo().performClick()

        compose.waitUntil(10_000) { signIn }
    }

    @Test
    fun aRecoveryCodeComebackTellsToLinkAnAccount() {
        show(RevokedVia.RECOVERY)

        compose.waitForText("카카오·Apple 계정을 연결해 두세요", substring = true)
        compose.screenshot("23-revoked-recovery", sheet = true)
    }
}
