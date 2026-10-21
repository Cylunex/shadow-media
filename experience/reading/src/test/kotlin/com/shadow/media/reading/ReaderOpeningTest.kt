package com.shadow.media.reading

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReaderOpeningTest {
    @Test fun opaqueEngineFailureHasAUsefulStageAndPreservesItsCause() = runBlocking {
        val cause = IllegalArgumentException("Failed requirement.")
        try { readerOpeningStep(ReaderOpenStage.PREFERENCES) { throw cause }; fail("must report failure") }
        catch (failure: ReaderOpeningException) {
            assertEquals(ReaderOpenStage.PREFERENCES, failure.stage)
            assertEquals("无法应用阅读排版设置，书籍和进度已保留", failure.message)
            assertSame(cause, failure.cause)
        }
    }
    @Test fun leavingReaderNeverTurnsCancellationIntoAnError() = runBlocking {
        val cancelled = CancellationException("test cancellation")
        try { readerOpeningStep(ReaderOpenStage.PUBLICATION) { throw cancelled }; fail("must cancel") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }
}
