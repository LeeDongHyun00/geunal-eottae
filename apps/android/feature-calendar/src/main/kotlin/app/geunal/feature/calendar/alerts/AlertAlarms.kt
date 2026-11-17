package app.geunal.feature.calendar.alerts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * The scheduled alerts on disk. AlarmManager cannot list its alarms and forgets them on a reboot, and the receiver that
 * shows an alert runs without the core, so the plan is kept here with the words each notification says. The caller puts
 * the file under `noBackupFilesDir`: it holds titles, which never leave the device (docs/kickoff.md §5.1), and it is
 * emptied on a sign-out or a deleted account.
 */
class AlertPlanStore(
    private val file: File,
) {
    /** Where [save] writes a plan before moving it over [file]. */
    private val next = File(file.path + ".next")

    /** Nothing when the file is missing or unreadable: the next refresh schedules everything again. */
    fun load(): List<PlannedAlert> {
        if (!file.exists()) return emptyList()
        return try {
            DataInputStream(ByteArrayInputStream(file.readBytes())).use { input ->
                if (input.readInt() != VERSION) return emptyList()
                List(input.readInt()) { read(input) }
            }
        } catch (e: IOException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            // A kind or a route this build does not know, or a length that makes no sense.
            emptyList()
        }
    }

    fun save(alerts: List<PlannedAlert>) {
        if (alerts.isEmpty()) {
            clear()
            return
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeInt(alerts.size)
            alerts.forEach { write(output, it) }
        }
        file.parentFile?.mkdirs()
        // Written beside the file and moved over it, so a process killed mid-write leaves the previous plan whole.
        next.writeBytes(bytes.toByteArray())
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun clear() {
        file.delete()
        // A process killed between the write and the move leaves this copy behind, and it holds event titles too:
        // nothing else removes it, so a sign-out would otherwise leave them on disk.
        next.delete()
    }

    private fun write(
        output: DataOutputStream,
        alert: PlannedAlert,
    ) {
        output.writeText(alert.id)
        output.writeLong(alert.fireAt.toEpochMilli())
        output.writeText(alert.title)
        output.writeText(alert.body)
        output.writeText(alert.kind.name)
        val route = alert.route.values()
        output.writeInt(route.size)
        route.forEach { (key, value) ->
            output.writeText(key)
            output.writeText(value)
        }
    }

    private fun read(input: DataInputStream): PlannedAlert {
        val id = input.readText()
        val fireAt = Instant.ofEpochMilli(input.readLong())
        val title = input.readText()
        val body = input.readText()
        val kind = AlertKind.valueOf(input.readText())
        val route = AlertRoute.of(List(input.readInt()) { input.readText() to input.readText() }.toMap())
        return PlannedAlert(id, fireAt, title, body, kind, requireNotNull(route))
    }

    // Length-prefixed UTF-8 rather than writeUTF, which refuses a string over 64 KB.
    private fun DataOutputStream.writeText(text: String) {
        val bytes = text.toByteArray()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 0..available())
        return String(ByteArray(size).also(::readFully))
    }

    private companion object {
        const val VERSION = 1
    }
}

/** AlarmManager behind a seam, so what is set when is tested without it. */
interface AlarmSetter {
    /** Sets an alarm that rings at [at] to the second. False when the system does not let this app set exact alarms. */
    fun exact(
        id: String,
        at: Instant,
    ): Boolean

    /** Sets an alarm the system may ring some minutes after [at], also while the phone idles. */
    fun inexact(
        id: String,
        at: Instant,
    )

    fun cancel(id: String)
}

/**
 * The scheduled alerts and their alarms, kept in step: the stored plan is what the app believes is set, and every
 * change to it sets or cancels the alarm with it. The app's scheduler and the broadcast receiver both come through
 * here, on different threads of one process, so every step holds one lock.
 */
class AlertAlarms(
    private val store: AlertPlanStore,
    private val setter: AlarmSetter,
    private val now: () -> Instant = Instant::now,
) {
    fun scheduled(): List<PlannedAlert> = synchronized(LOCK) { store.load() }

    fun schedule(alert: PlannedAlert) {
        synchronized(LOCK) {
            store.save(store.load().filterNot { it.id == alert.id } + alert)
            arm(alert)
        }
    }

    fun cancel(ids: Collection<String>) {
        synchronized(LOCK) {
            ids.forEach(setter::cancel)
            store.save(store.load().filterNot { it.id in ids })
        }
    }

    /**
     * Sets every alarm again: after a reboot, an update of the app, a clock or time zone change, a force stop, or a
     * change of the exact alarm access. An alert whose time passed meanwhile still rings, at once, while it is no later
     * than [AlertScheduler.DUE_GRACE]; an older one is dropped, since its words ("10분 뒤") would no longer be true.
     */
    fun rearm() {
        synchronized(LOCK) {
            val all = store.load()
            val oldest = now().minus(AlertScheduler.DUE_GRACE)
            val (kept, lapsed) = all.partition { it.fireAt.isAfter(oldest) }
            if (lapsed.isNotEmpty()) {
                lapsed.forEach { setter.cancel(it.id) }
                store.save(kept)
            }
            kept.forEach(::arm)
        }
    }

    /**
     * The alert whose alarm rang, which is then no longer scheduled. `null` when it was cancelled meanwhile, or
     * everything was removed by a sign-out: nothing is shown then.
     */
    fun take(id: String): PlannedAlert? =
        synchronized(LOCK) {
            val all = store.load()
            val alert = all.firstOrNull { it.id == id } ?: return null
            store.save(all - alert)
            alert
        }

    fun clear() {
        synchronized(LOCK) {
            store.load().forEach { setter.cancel(it.id) }
            store.clear()
        }
    }

    /** Exact when the system allows it, and otherwise an alarm that may ring late rather than none (QA m1.md D5). */
    private fun arm(alert: PlannedAlert) {
        if (!setter.exact(alert.id, alert.fireAt)) setter.inexact(alert.id, alert.fireAt)
    }

    private companion object {
        /** One per process: the receiver makes its own instance over the same file. */
        val LOCK = Any()
    }
}
