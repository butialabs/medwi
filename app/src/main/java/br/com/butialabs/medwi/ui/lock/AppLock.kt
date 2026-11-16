package br.com.butialabs.medwi.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.data.settings.AppSettings

/**
 * App lock using the phone's own screen lock (fingerprint, face, PIN, pattern) through
 * BiometricPrompt: no extra password to forget. Locks on launch and after
 * [RELOCK_AFTER_MILLIS] in the background.
 */
class AppLock(private val activity: FragmentActivity, alreadyUnlocked: Boolean = false) {

    /** Observed by Compose: while true, the lock screen covers the app. */
    var locked by mutableStateOf(AppSettings.isLockEnabled(activity) && !alreadyUnlocked)
        private set

    private var backgroundedAt = 0L
    private var prompting = false

    fun onStop() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onStart() {
        if (!AppSettings.isLockEnabled(activity)) {
            locked = false
            return
        }
        if (backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MILLIS) {
            locked = true
        }
    }

    /** Called after the lock setting is switched on in Settings (we just authenticated). */
    fun onEnabled() { locked = false }

    fun prompt() {
        if (prompting || !locked) return
        prompting = true
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    locked = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                }
            },
        )
        prompt.authenticate(promptInfo(activity.getString(R.string.lock_prompt_title)))
    }

    companion object {
        const val RELOCK_AFTER_MILLIS = 60_000L
        private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

        fun promptInfo(title: String): BiometricPrompt.PromptInfo =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()

        /** The phone has some screen lock the prompt can use. */
        fun isAvailable(context: Context): Boolean {
            val status = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)
            if (status == BiometricManager.BIOMETRIC_SUCCESS) return true
            // Older APIs may not report DEVICE_CREDENTIAL; a secure keyguard is enough.
            return context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        }

        /** Ask the user to authenticate before turning the lock on; [onSuccess] enables it. */
        fun confirmThenEnable(activity: FragmentActivity, onSuccess: () -> Unit) {
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        AppSettings.setLockEnabled(activity, true)
                        onSuccess()
                    }
                },
            ).authenticate(promptInfo(activity.getString(R.string.lock_title)))
        }
    }
}

/** Full-screen cover shown while the app is locked; asks for authentication right away. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_medwi_mark), contentDescription = null, modifier = Modifier.size(88.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.lock_locked), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) { Text(stringResource(R.string.lock_unlock)) }
    }
}
