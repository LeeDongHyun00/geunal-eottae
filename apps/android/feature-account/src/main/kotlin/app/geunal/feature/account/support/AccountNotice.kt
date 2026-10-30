package app.geunal.feature.account.support

import androidx.annotation.StringRes
import app.geunal.feature.account.R

/** What a model asks the toast to say. The models stay free of Android resources; the flow turns these into text. */
enum class AccountNotice(
    @param:StringRes val text: Int,
) {
    OFFLINE(R.string.account_notice_offline),
    SIGN_IN_FAILED(R.string.account_notice_sign_in_failed),
    SETTING_FAILED(R.string.account_notice_setting_failed),
    SOMETHING_FAILED(R.string.account_notice_something_failed),
    TICKET_EXPIRED(R.string.account_notice_ticket_expired),
    AGE_CHECK_NEEDED(R.string.account_notice_age_check_needed),
}
