package app.rewapp

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Insistencias: si la persona no contestó en el plazo de la regla, se manda el mensaje de seguimiento. */
object FollowUps {
    private const val WORK_NAME = "rewapp_followups"

    /** Programa un aviso para el próximo seguimiento pendiente (si hay). */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        val next = Store.allStates().filter { it.followUpDueAt > 0 }.minOfOrNull { it.followUpDueAt }
        if (next == null) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = (next - System.currentTimeMillis()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<FollowUpWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    /** Cancela a mano la insistencia pendiente de un contacto. */
    fun cancel(ctx: Context, key: String) {
        Store.updateState(key) { it?.withoutFollowUp() }
        schedule(ctx)
    }

    fun runDue(ctx: Context) {
        val settings = Store.settings()
        val rules = Store.rules()
        val now = System.currentTimeMillis()
        for (candidate in Store.allStates()) {
            if (candidate.followUpDueAt <= 0 || candidate.followUpDueAt > now) continue
            // Se vuelve a leer por si la persona contestó mientras tanto.
            val st = Store.state(candidate.key) ?: continue
            if (st.followUpDueAt <= 0 || st.followUpDueAt > now) continue
            val label = st.name.ifBlank { st.key }

            val rule = rules.find { it.id == st.followUpRuleId }
            if (!settings.enabled || rule == null || !rule.enabled || !rule.followUpEnabled) {
                Store.updateState(st.key) { cur -> if (cur != null && cur.followUpDueAt == st.followUpDueAt) cur.withoutFollowUp() else cur }
                continue
            }

            // Última comprobación: ¿la conversación siguió y no nos enteramos?
            if (Replier.hasUnseenActivity(st.key, st.lastProcessedMsgTime, st.lastSent)) {
                Store.updateState(st.key) { cur -> if (cur != null && cur.followUpDueAt == st.followUpDueAt) cur.withoutFollowUp() else cur }
                Store.log(LogEntry(now, label, "", rule.name, "Insistencia cancelada: hay actividad nueva en el chat"))
                continue
            }

            // followUpAttempts = cuántas insistencias ya se mandaron; la que toca ahora es la siguiente de la lista.
            val steps = rule.activeFollowUps
            val current = steps.getOrNull(st.followUpAttempts)
            if (current == null) {
                Store.updateState(st.key) { cur -> if (cur != null && cur.followUpDueAt == st.followUpDueAt) cur.withoutFollowUp() else cur }
                continue
            }
            val text = Engine.fill(current.message, st.name)
            val ok = Replier.send(ctx, st.key, text)
            val attempts = st.followUpAttempts + 1
            val next = steps.getOrNull(attempts)
            val more = ok && next != null
            // Solo se guarda si nadie tocó el estado mientras tanto (por ejemplo, si la persona escribió).
            Store.updateState(st.key) { cur ->
                if (cur == null || cur.followUpDueAt != st.followUpDueAt) cur
                else cur.copy(
                    followUpAttempts = attempts,
                    followUpDueAt = if (more && next != null) now + next.delayMinutes * 60_000L else 0,
                    followUpRuleId = if (more) cur.followUpRuleId else null,
                    lastSent = if (ok) (cur.lastSent + Engine.normalize(text)).takeLast(6) else cur.lastSent,
                )
            }
            Store.log(
                LogEntry(
                    now, label, "(sin respuesta)", rule.name,
                    if (ok) "Insistencia $attempts/${steps.size} enviada"
                    else "No se pudo insistir: la notificación del chat ya no está disponible",
                ),
            )
        }
    }
}

class FollowUpWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        FollowUps.runDue(applicationContext)
        FollowUps.schedule(applicationContext)
        return Result.success()
    }
}
