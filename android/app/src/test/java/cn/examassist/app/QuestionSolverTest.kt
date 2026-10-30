package cn.examassist.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class QuestionSolverTest {
    @Test fun completeBankAnswersWinEvenWithAiEnabledAndNoNetworkCredentials() {
        val row = JSONObject().put("stem", "测试题").put("reference_answer", "B")
            .put("options", JSONArray().put(JSONObject().put("label", "A").put("text", "甲"))
                .put(JSONObject().put("label", "B").put("text", "乙")))
        val lookup = LocalBankMatching.lookup("测试题\nA. 乙\nB. 甲", listOf(row))
        assertEquals("A", QuestionSolver.bankAnswer(AiConfig(aiEnabled = true, apiKey = ""), lookup)?.getString("answer"))
        assertEquals("local_bank", QuestionSolver.bankAnswer(AiConfig(aiEnabled = false), lookup)?.getString("answer_source"))
    }

    @Test fun unusableBankResultsFallBackOnlyWhenAiIsEnabled() {
        for (lookup in listOf(LocalBankMatching.Lookup.Miss("未匹配"), LocalBankMatching.Lookup.Blocked("答案冲突"))) {
            assertNull(QuestionSolver.bankAnswer(vision, lookup))
            val error = runCatching { QuestionSolver.bankAnswer(local, lookup) }.exceptionOrNull()
            assertTrue(error is ApiException)
            assertEquals(if (lookup is LocalBankMatching.Lookup.Miss) lookup.reason else (lookup as LocalBankMatching.Lookup.Blocked).reason, error?.message)
        }
    }

    @Test fun missingConflictingAndUnmappableBankAnswersAllowAiFallback() {
        fun row(answer: String) = JSONObject().put("stem", "测试题").put("reference_answer", answer)
            .put("options", JSONArray().put(JSONObject().put("label", "A").put("text", "甲"))
                .put(JSONObject().put("label", "B").put("text", "乙")))
        val question = "测试题\nA. 甲\nB. 乙"
        for (rows in listOf(listOf(row("")), listOf(row("C")), listOf(row("A"), row("B")))) {
            val lookup = LocalBankMatching.lookup(question, rows)
            assertTrue(lookup is LocalBankMatching.Lookup.Blocked)
            assertNull(QuestionSolver.bankAnswer(vision, lookup))
            assertTrue(runCatching { QuestionSolver.bankAnswer(local, lookup) }.exceptionOrNull() is ApiException)
        }
        val changedOptions = LocalBankMatching.lookup("测试题\nA. 甲\nB. 丙", listOf(row("B")), threshold = 80)
        assertTrue(changedOptions is LocalBankMatching.Lookup.Blocked)
        assertNull(QuestionSolver.bankAnswer(vision, changedOptions))
    }
    private val vision = AiConfig()
    private val text = AiConfig(provider = "custom", model = "text", imageInput = false)
    private val local = vision.copy(aiEnabled = false, ocrEnabled = false)

    @Test fun accessiblePageTextPreservesQuestionsAndRejectsMissingOrOversizedMaterial() {
        assertEquals("题干\nA.甲\nB.乙", QuestionSolver.validatePageText(" 题干\nA.甲\nB.乙 \n"))
        assertEquals(20000, QuestionSolver.validatePageText("题".repeat(20000)).length)
        for (question in listOf(" \n\t", "题".repeat(20001))) {
            assertTrue(runCatching { QuestionSolver.validatePageText(question) }.exceptionOrNull() is ApiException)
        }
    }

    @Test fun visualModelsContinueFromTimeoutFailureBlankAndOversizedOcr() = runBlocking {
        assertEquals("", QuestionSolver.readQuestion(vision, timeoutMillis = 10) { awaitCancellation() })
        assertEquals("", QuestionSolver.readQuestion(vision) { throw IllegalStateException("OCR failed") })
        assertEquals("", QuestionSolver.readQuestion(vision) { " \n " })
        assertEquals("", QuestionSolver.readQuestion(vision) { "题".repeat(20001) })
        assertEquals("题目\nA.甲\nB.乙", QuestionSolver.readQuestion(vision) { " 题目\nA.甲\nB.乙 " })
        assertEquals("题".repeat(20000), QuestionSolver.readQuestion(vision) { "题".repeat(20000) })
    }

    @Test fun textAndLocalModesNeedOcrAndOfferAvailableRecoveryActions() = runBlocking {
        for (config in listOf(text, local)) {
            for (recognize in listOf<suspend () -> String>({ "" }, { throw IllegalStateException("failed") }, { awaitCancellation() })) {
                val error = runCatching { QuestionSolver.readQuestion(config, timeoutMillis = 10, recognize = recognize) }.exceptionOrNull()
                assertTrue(error is ApiException)
                assertTrue(error?.message.orEmpty().contains("题目文字"))
                assertFalse(error?.message.orEmpty().contains("系统截图"))
                assertTrue(error?.message.orEmpty().contains(if (config.aiEnabled) "视觉模型" else "读取页面文字"))
            }
            assertEquals("识别出的文字", QuestionSolver.readQuestion(config) { "识别出的文字" })
        }
    }

    @Test fun disabledOcrSkipsRecognitionOnlyForVisualAi() = runBlocking {
        assertEquals("", QuestionSolver.readQuestion(vision.copy(ocrEnabled = false)) { error("OCR must be skipped") })
        assertEquals("题干", QuestionSolver.readQuestion(text.copy(ocrEnabled = false)) { "题干" })
        assertEquals("题干", QuestionSolver.readQuestion(local) { "题干" })
    }

    @Test fun externalCancellationAndExternalTimeoutDoNotBecomeImageFallback() = runBlocking {
        coroutineScope {
            val started = CompletableDeferred<Unit>()
            val task = async {
                QuestionSolver.readQuestion(vision) { started.complete(Unit); awaitCancellation() }
            }
            started.await()
            task.cancel()
            val error = runCatching { task.await() }.exceptionOrNull()
            assertTrue(error is CancellationException)
        }
        val timeout = runCatching {
            withTimeout(10) { QuestionSolver.readQuestion(vision) { awaitCancellation() } }
        }.exceptionOrNull()
        assertTrue(timeout is CancellationException)
        val recognizerCancellation = runCatching {
            QuestionSolver.readQuestion(vision) { throw CancellationException("caller cancelled") }
        }.exceptionOrNull()
        assertTrue(recognizerCancellation is CancellationException)
    }
}
