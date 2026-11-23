package com.example.skipad

/** 独立于 Android 定时器的确认/去重状态。未知页面只取消候选，不解除已执行锁。 */
internal class AdSkipGate {
    data class Decision(val send: Boolean = false, val recheckMs: Long? = null, val newCandidate: Boolean = false)
    private data class Candidate(val scope: String, val confidence: AdConfidence, val firstAt: Long)
    private data class ClearState(val scope: String, val since: Long)
    private var candidate: Candidate? = null
    private val attemptedPackages = mutableSetOf<String>()
    private val clearSince = mutableMapOf<String, ClearState>()
    private var nextAllowedAt = 0L

    fun cancelCandidate() {
        candidate = null
        clearSince.clear()
    }

    fun evaluate(observation: AdObservation?, now: Long): Decision {
        if (observation == null || !observation.inPlayer) {
            cancelCandidate()
            return Decision()
        }
        val pkg = observation.packageName
        if (observation.confidence == AdConfidence.NONE) {
            candidate = null
            // 其他应用/窗口的无广告状态不解除这个包的锁。
            clearSince.keys.retainAll(setOf(pkg))
            if (pkg in attemptedPackages) {
                val previous = clearSince[pkg]
                val state = if (previous?.scope == observation.scope) previous
                    else ClearState(observation.scope, now).also { clearSince[pkg] = it }
                val remaining = 800L - (now - state.since)
                if (remaining > 0) return Decision(recheckMs = remaining)
                attemptedPackages.remove(pkg)
                clearSince.remove(pkg)
            }
            return Decision()
        }
        clearSince.clear()
        if (!observation.isAd || pkg in attemptedPackages) {
            candidate = null
            return Decision()
        }
        val previous = candidate
        val changed = previous == null || previous.scope != observation.scope ||
            previous.confidence != observation.confidence
        if (changed) candidate = Candidate(observation.scope, observation.confidence, now)
        val current = candidate!!
        val dueAt = maxOf(current.firstAt + current.confidence.confirmMs, nextAllowedAt)
        return if (now >= dueAt) Decision(send = true)
        else Decision(recheckMs = dueAt - now, newCandidate = changed)
    }

    fun markSent(observation: AdObservation, now: Long) {
        attemptedPackages.add(observation.packageName)
        candidate = null
        clearSince.clear()
        nextAllowedAt = now + 700L
    }

    fun markFailed(now: Long) {
        candidate = null
        nextAllowedAt = now + 700L
    }
}
