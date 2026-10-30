package app.geunal

import android.app.Application
import app.geunal.feature.account.providers.ForegroundActivity
import app.geunal.feature.account.providers.KakaoSignIn

class GeunalApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The provider screens start from the activity in front (Kakao's SDK, the Apple Custom Tab, the OS prompt).
        ForegroundActivity.install(this)
        KakaoSignIn.configure(this, BuildConfig.KAKAO_APP_KEY)
        container = AppContainer(this)
    }
}
