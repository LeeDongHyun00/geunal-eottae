package app.geunal.feature.account.onboarding

import app.geunal.core.AuthResult
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.core.NewAccount
import app.geunal.core.Provider
import app.geunal.feature.account.providers.ProviderSignIn
import app.geunal.feature.account.providers.ProviderSignInException
import app.geunal.feature.account.restore.RestoreMode
import app.geunal.feature.account.support.AccountNotice
import app.geunal.feature.account.support.OnboardingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * The sign-in screen (m1.md screen 2): the age check, Kakao, Apple and guest. Each action returns where onboarding goes
 * next, or `null` when nothing changes (cancelled, failed with a toast, or already running).
 */
class SignInModel(
    private val core: CoreInterface,
    private val providers: ProviderSignIn,
    private val onboarding: OnboardingState,
    private val notify: (AccountNotice) -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    enum class Busy { KAKAO, APPLE, GUEST }

    sealed interface Outcome {
        data object Permissions : Outcome

        data class Restore(
            val mode: RestoreMode,
        ) : Outcome
    }

    data class State(
        /** When the box was ticked; only this time goes to the server, never a birth date (kickoff §6). */
        val ageConfirmedAt: Instant? = null,
        val busy: Busy? = null,
    ) {
        val isAgeConfirmed: Boolean get() = ageConfirmedAt != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setAgeConfirmed(confirmed: Boolean) {
        _state.update { it.copy(ageConfirmedAt = if (confirmed) now() else null) }
    }

    suspend fun signIn(
        provider: Provider,
        isOnline: Boolean,
    ): Outcome? {
        val ageConfirmedAt = _state.value.ageConfirmedAt ?: return null
        if (_state.value.busy != null) return null
        if (!isOnline) {
            notify(AccountNotice.OFFLINE)
            return null
        }
        _state.update { it.copy(busy = if (provider == Provider.KAKAO) Busy.KAKAO else Busy.APPLE) }
        try {
            val nonce = core.signInNonce()
            val result =
                when (provider) {
                    Provider.KAKAO -> {
                        val credential = providers.kakao(nonce.providerNonce)
                        core.signInKakao(nonce.nonce, credential.idToken, account(credential.displayName, ageConfirmedAt))
                    }
                    Provider.APPLE -> {
                        // The code that comes with Apple's answer is for account deletion only (protocol §13).
                        val credential = providers.apple(nonce.providerNonce)
                        core.signInApple(nonce.nonce, credential.idToken, account(credential.displayName, ageConfirmedAt))
                    }
                }
            onboarding.pendingGuestAgeConfirmedAt = null
            return outcome(result)
        } catch (e: ProviderSignInException.Cancelled) {
            return null
        } catch (e: CoreException.Network) {
            notify(AccountNotice.OFFLINE)
            return null
        } catch (e: ProviderSignInException) {
            notify(AccountNotice.SIGN_IN_FAILED)
            return null
        } catch (e: CoreException) {
            notify(AccountNotice.SIGN_IN_FAILED)
            return null
        } catch (e: InternalException) {
            notify(AccountNotice.SIGN_IN_FAILED)
            return null
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /**
     * "게스트로 둘러보기". Offline, the guest goes in with only the ticked time kept on the device, and the account is
     * made on the first connection (m1.md decision 16). The same happens while the core cannot make guest accounts yet,
     * so the calendar is usable before the server part lands.
     */
    suspend fun continueAsGuest(isOnline: Boolean): Outcome? {
        val ageConfirmedAt = _state.value.ageConfirmedAt ?: return null
        if (_state.value.busy != null) return null
        if (!isOnline) {
            onboarding.pendingGuestAgeConfirmedAt = ageConfirmedAt
            return Outcome.Permissions
        }
        _state.update { it.copy(busy = Busy.GUEST) }
        try {
            core.signInGuest(account(null, ageConfirmedAt))
            onboarding.pendingGuestAgeConfirmedAt = null
            return Outcome.Permissions
        } catch (e: CoreException) {
            if (e is CoreException.Network || e is CoreException.NotImplemented) {
                onboarding.pendingGuestAgeConfirmedAt = ageConfirmedAt
                return Outcome.Permissions
            }
            notify(AccountNotice.SIGN_IN_FAILED)
            return null
        } catch (e: InternalException) {
            notify(AccountNotice.SIGN_IN_FAILED)
            return null
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    companion object {
        /**
         * The account a sign-in makes when it is new. The name goes as the provider gave it, empty when there is none:
         * the core fills in "카카오 사용자", "Apple 사용자" or "게스트" and cuts it to the server's 40 (core PR #38).
         */
        fun account(
            displayName: String?,
            ageConfirmedAt: Instant,
        ) = NewAccount(displayName = displayName?.trim().orEmpty(), ageConfirmedAt = ageConfirmedAt)

        fun outcome(result: AuthResult): Outcome =
            when (result) {
                // An existing account with recovery material on the server: offer its backup (protocol §13).
                is AuthResult.Registered ->
                    if (!result.newAccount && result.recovery) Outcome.Restore(RestoreMode.OFFER) else Outcome.Permissions
                is AuthResult.ReplaceRequired -> Outcome.Restore(RestoreMode.DEVICE_CHANGE)
            }
    }
}

/** A guest who started offline gets the account on a later launch with a connection (m1.md decision 16). */
object PendingGuest {
    suspend fun complete(
        core: CoreInterface,
        onboarding: OnboardingState,
    ) {
        val ageConfirmedAt = onboarding.pendingGuestAgeConfirmedAt ?: return
        if (core.session() != null) {
            onboarding.pendingGuestAgeConfirmedAt = null
            return
        }
        try {
            core.signInGuest(SignInModel.account(null, ageConfirmedAt))
            onboarding.pendingGuestAgeConfirmedAt = null
        } catch (e: CoreException) {
            // Kept for the next launch; the calendar works without an account meanwhile.
        } catch (e: InternalException) {
            // As above.
        }
    }
}
