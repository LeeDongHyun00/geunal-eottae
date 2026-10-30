package app.geunal.feature.account.providers

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * The activity in front, for the provider screens that must start from one (the Kakao SDK and Custom Tabs). Held
 * weakly, so a finished activity is never kept alive.
 */
object ForegroundActivity : Application.ActivityLifecycleCallbacks {
    private var current: WeakReference<Activity>? = null
    private val resumeListeners = mutableListOf<(Activity) -> Unit>()

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun get(): Activity? = current?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    /** Called on every resume until removed; the Apple sign-in uses it to notice that its browser tab was closed. */
    fun addResumeListener(listener: (Activity) -> Unit) {
        synchronized(resumeListeners) { resumeListeners += listener }
    }

    fun removeResumeListener(listener: (Activity) -> Unit) {
        synchronized(resumeListeners) { resumeListeners -= listener }
    }

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
        val listeners = synchronized(resumeListeners) { resumeListeners.toList() }
        listeners.forEach { it(activity) }
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (current?.get() === activity) current = null
    }
}
