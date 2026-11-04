package app.geunal.api.push

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `geunal.push.*`: the APNs key and the FCM service account (protocol §8). Either left empty turns that platform's
 * pushes off; the rest of the server runs the same.
 */
@ConfigurationProperties("geunal.push")
data class PushProperties(
    val apns: Apns = Apns(),
    val fcm: Fcm = Fcm(),
) {
    data class Apns(
        val keyId: String = "",
        /** The .p8 (PEM PKCS#8, P-256); an environment value may carry `\n` for its line breaks. */
        val privateKey: String = "",
        val teamId: String = "",
        /** The iOS app's bundle ID. */
        val topic: String = "app.geunal.ios",
        /** Production only: TestFlight and App Store builds hold production tokens, and M2 has no other builds to reach. */
        val endpoint: String = "https://api.push.apple.com",
    ) {
        val enabled: Boolean get() = keyId.isNotBlank() && privateKey.isNotBlank() && teamId.isNotBlank()

        override fun toString(): String = "Apns(keyId=$keyId, teamId=$teamId, topic=$topic, endpoint=$endpoint)"
    }

    data class Fcm(
        /** The service account's JSON key file, as text. */
        val serviceAccount: String = "",
        val endpoint: String = "https://fcm.googleapis.com",
    ) {
        val enabled: Boolean get() = serviceAccount.isNotBlank()

        override fun toString(): String = "Fcm(endpoint=$endpoint)"
    }
}
