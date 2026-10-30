package app.geunal.feature.account

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.geunal.feature.account.providers.KakaoSignIn
import app.geunal.feature.account.providers.MemoryTokenManager
import com.kakao.sdk.auth.TokenManagerProvider
import com.kakao.sdk.auth.model.OAuthToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/** Kakao's tokens never reach the SDK's SharedPreferences, which backups and device transfers copy. */
@RunWith(AndroidJUnit4::class)
class KakaoTokenStorageTest {
    @Test
    fun keepsKakaoTokensInMemoryOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        KakaoSignIn.configure(context, APP_KEY)
        val manager = TokenManagerProvider.instance.manager
        assertTrue(manager.javaClass.name, manager is MemoryTokenManager)

        // The SDK names its preferences after the app key.
        val preferences = context.getSharedPreferences(APP_KEY, Context.MODE_PRIVATE)
        val before = preferences.all.keys.toSet()
        manager.setToken(OAuthToken("access", Date(), "refresh", Date(), "id", emptyList()))
        assertEquals("access", manager.getToken()?.accessToken)
        assertEquals(before, preferences.all.keys.toSet())

        manager.clear()
        assertNull(manager.getToken())
    }

    private companion object {
        // Any key will do: init asks Kakao nothing.
        const val APP_KEY = "0123456789abcdef0123456789abcdef"
    }
}
