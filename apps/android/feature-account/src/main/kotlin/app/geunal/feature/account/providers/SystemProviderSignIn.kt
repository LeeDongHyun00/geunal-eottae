package app.geunal.feature.account.providers

/** The real providers: Kakao through its SDK, Apple through the web flow. */
class SystemProviderSignIn(
    private val kakaoSignIn: KakaoSignIn,
    private val appleSignIn: AppleWebSignIn,
) : ProviderSignIn {
    override suspend fun kakao(providerNonce: String): ProviderCredential = kakaoSignIn.signIn(providerNonce)

    override suspend fun apple(providerNonce: String): ProviderCredential = appleSignIn.signIn(providerNonce)

    /** Only Apple's web flow comes back through a new activity; Kakao's SDK answers the activity that asked. */
    override fun takeInterrupted(): Boolean = AppleCallbacks.takeInterrupted()
}
