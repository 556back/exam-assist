package cn.examassist.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Requires the page-text service enabled by the tester in the emulator's system settings. */
internal object ProtectedPageTextCheck {
    fun run(instrumentation: Instrumentation): List<String> = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.sendStatus(1, Bundle().apply { putString("stream", "WAITING_FOR_ACCESSIBILITY\n") })
        check(withTimeoutOrNull(30000) {
            while (!PageTextService.isConnected) delay(100)
            true
        } == true) { "PageTextService did not connect after MainActivity startup; re-enable it after instrumentation force-stop on the emulator." }
        val ownPackage = instrumentation.targetContext.packageName
        instrumentation.context.startActivity(Intent().setClassName(instrumentation.context.packageName,
            ProtectedQuestionActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val question = withTimeout(10000) {
            var text = ""
            while (!text.contains("太阳从哪个方向升起")) {
                delay(100)
                text = runCatching { PageTextService.readCurrentQuestion(ownPackage) }.getOrDefault("")
            }
            text
        }
        check(listOf("A. 东", "B. 西", "C. 南", "D. 北").all(question::contains)) { "Protected window option text was incomplete." }
        check(!question.contains("答题助手")) { "Own overlay text entered the question material." }
        val passed = listOf("real FLAG_SECURE window exposes its complete question and options through explicitly enabled accessibility, without screenshots") +
            ScreenshotSolveCheck.run(instrumentation.targetContext, question)
        instrumentation.runOnMainSync { activity.finish() }
        return@runBlocking passed
    }
}
