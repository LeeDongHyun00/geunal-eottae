package app.geunal.feature.account

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import app.geunal.feature.account.support.SystemDeviceAuth
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAuthTest {
    @Test
    fun asksForAStrongBiometricOrTheScreenLock() {
        for (sdk in listOf(26, 27, 30, 35, 36)) {
            assertEquals("API $sdk", BIOMETRIC_STRONG or DEVICE_CREDENTIAL, SystemDeviceAuth.authenticators(sdk))
        }
    }

    @Test
    fun takesAWeakBiometricOnAndroid9And10() {
        // androidx.biometric refuses a strong biometric with the screen lock on exactly these two.
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, SystemDeviceAuth.authenticators(28))
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, SystemDeviceAuth.authenticators(29))
    }
}
