package top.cylunex.shadowmedia.model

import org.junit.Assert.*
import org.junit.Test

class ResumeValidationTest {
    private fun valid(status: Int = 206, range: String = "bytes 100-199/200", length: Long = 100, expected: String = "E:version", actual: String = "E:version") =
        ResumeValidation.accepts(100, status, true, range, length, expected, actual)
    @Test fun `resume requires matching validator and exact range`() {
        assertTrue(valid())
        assertFalse(valid(status = 200))
        assertFalse(valid(expected = ""))
        assertFalse(valid(actual = "E:changed"))
        assertFalse(valid(range = "bytes 0-99/200"))
        assertFalse(valid(range = "bytes 100-200/200"))
        assertFalse(valid(length = 99))
        assertFalse(valid(range = "bytes 100-199/*"))
    }
    @Test fun `initial download accepts full response but rejects unsolicited partial`() {
        assertTrue(ResumeValidation.accepts(0, 200, false, null, 200, "", ""))
        assertFalse(ResumeValidation.accepts(0, 206, false, "bytes 0-99/200", 100, "", ""))
        assertFalse(ResumeValidation.accepts(0, 204, false, null, 0, "", ""))
    }
}
