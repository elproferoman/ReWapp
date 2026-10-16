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

    fun runDue(ctx: Context) {
        val settings = Store.settings()
        val rules = Store.rules()
        val now = System.currentTimeMillis()
        for (candidate in Store.allStates()) {
            if (candidate.followUpDueAt <= 0 || candidate.followUpDueAt > now) continue
            // Se vuelve a leer por si la persona contestó mientras tanto.
            val st = Store.state(candidate.key) ?: continue
            if (st.followUpDueAt <= 0 || st.followUpDueAt > now) continue

            val rule = rules.find { it.id == st.followUpRuleId }
            if (!settings.enabled || rule == null || !rule.enabled || !rule.followUpEnabled) {
                Store.putState(st.withoutFollowUp())
                continue
            }

            val text = Engine.fill(rule.followUpMessage, st.key)
            val ok = Replier.send(ctx, st.key, text)
            val attempts = st.followUpAttempts + 1
            val more = ok && attempts < rule.followUpMaxAttempts
            Store.putState(
                st.copy(
                    followUpAttempts = attempts,
                    followUpDueAt = if (more) now + rule.followUpDelayMinutes * 60_000L else 0,
                    followUpRuleId = if (more) st.followUpRuleId else null,
                    lastSent = if (ok) (st.lastSent + Engine.normalize(text)).takeLast(6) else st.lastSent,
                ),
            )
            Store.log(
                LogEntry(
                    now, st.key, "(sin respuesta)", rule.name,
                    if (ok) "Insistencia $attempts/${rule.followUpMaxAttempts} enviada"
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
