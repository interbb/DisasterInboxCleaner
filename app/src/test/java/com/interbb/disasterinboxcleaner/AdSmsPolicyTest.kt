package com.interbb.disasterinboxcleaner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSmsPolicyTest {
    @Test
    fun selectionsPinInboxTypeAndBothMarkersAnywhereInBody() {
        assertEquals("(body LIKE '%(광고)%' OR body LIKE '%[광고]%')", AdSmsPolicy.candidateMarkSelection)
        assertEquals(
            "type = 1 AND (body LIKE '%(광고)%' OR body LIKE '%[광고]%')",
            AdSmsPolicy.inboxAdSelection,
        )
        assertEquals(
            "type = 1 AND (body LIKE '%(광고)%' OR body LIKE '%[광고]%') AND date >= ?",
            AdSmsPolicy.autoDeleteSelection,
        )
        assertEquals(
            "_id = ? AND type = 1 AND (body LIKE '%(광고)%' OR body LIKE '%[광고]%')",
            AdSmsPolicy.byIdSelection,
        )
        assertArrayEquals(arrayOf("1700000000000"), AdSmsPolicy.autoDeleteSelectionArgs(1_700_000_000_000L))
        assertArrayEquals(arrayOf("42"), AdSmsPolicy.byIdSelectionArgs(42L))
    }

    @Test
    fun isAdAcceptsMarkAtStart() {
        assertTrue(AdSmsPolicy.isAd("(광고)여름 세일 안내"))
        assertTrue(AdSmsPolicy.isAd("[광고] 이벤트 안내"))
    }

    @Test
    fun isAdAcceptsMarkAfterLeadingBlankLines() {
        assertTrue(AdSmsPolicy.isAd("\n\n(광고)줄바꿈 뒤 광고"))
    }

    @Test
    fun isAdAcceptsMarkAfterOneCarrierTagOnItsOwnLine() {
        assertTrue(AdSmsPolicy.isAd("[Web발신]\n(광고) 본문"))
    }

    @Test
    fun isAdAcceptsMarkAfterOneCarrierTagOnSameLine() {
        assertTrue(AdSmsPolicy.isAd("[Web발신] (광고) 본문"))
    }

    @Test
    fun isAdAcceptsMarkAfterOneCarrierTagWithCarriageReturn() {
        assertTrue(AdSmsPolicy.isAd("[국제발신]\r\n[광고]본문"))
    }

    @Test
    fun isAdAcceptsMarkAfterTwoCarrierTags() {
        assertTrue(AdSmsPolicy.isAd("[국제발신][Web발신](광고)본문"))
    }

    @Test
    fun isAdAcceptsMarkAfterThreeCarrierTags() {
        assertTrue(AdSmsPolicy.isAd("[국제발신][국외발신][Web발신](광고)본문"))
    }

    @Test
    fun isAdAcceptsCarrierTagWithInternalSpaceAndDifferentCase() {
        assertTrue(AdSmsPolicy.isAd("[Web 발신](광고)본문"))
        assertTrue(AdSmsPolicy.isAd("[web발신](광고)본문"))
    }

    @Test
    fun isAdAcceptsKnownCarrierTagAtLengthLimit() {
        // Inner text is 19 chars ("국제발신" + 15 spaces), putting the closing bracket exactly at
        // the edge of the allowed search window; normalizing strips the padding back to a match.
        val innerAtLimit = "국제발신" + " ".repeat(15)
        assertEquals(19, innerAtLimit.length)
        assertTrue(AdSmsPolicy.isAd("[$innerAtLimit](광고)본문"))
    }

    @Test
    fun isAdRejectsCarrierTagJustOverLengthLimit() {
        // One character longer than the accepted case above: the closing bracket now falls just
        // past the search window, so the tag is never recognized even though it would normalize
        // to a known tag.
        val innerOverLimit = "국제발신" + " ".repeat(16)
        assertEquals(20, innerOverLimit.length)
        assertFalse(AdSmsPolicy.isAd("[$innerOverLimit](광고)본문"))
    }

    @Test
    fun isAdRejectsFourthCarrierTag() {
        assertFalse(AdSmsPolicy.isAd("[Web발신][국제발신][국외발신][해외발신](광고)본문"))
    }

    @Test
    fun isAdRejectsSafetyNoticeHeaderMisreadAsCarrierTagRegression() {
        // Regression guard: "[안전안내문자]" is not a known carrier tag, so it must never be
        // skipped as one — doing so would let a public safety message be deleted as an ad.
        assertFalse(AdSmsPolicy.isAd("[안전안내문자]\n(광고) 표시가 없는 문자는 불법입니다"))
    }

    @Test
    fun isAdRejectsUnknownTagEvenWhenMarkFollowsIt() {
        assertFalse(AdSmsPolicy.isAd("[국세청](광고)본문"))
    }

    @Test
    fun isAdRejectsMarkNotAtStart() {
        assertFalse(AdSmsPolicy.isAd("안녕하세요 (광고) 아님"))
    }

    @Test
    fun isAdRejectsKnownCarrierTagWithNoMarkAfterIt() {
        assertFalse(AdSmsPolicy.isAd("[Web발신]\n안녕"))
    }

    @Test
    fun isAdRejectsBodyThatIsOnlyACarrierTag() {
        assertFalse(AdSmsPolicy.isAd("[Web발신]"))
    }

    @Test
    fun isAdRejectsUnclosedCarrierTag() {
        assertFalse(AdSmsPolicy.isAd("[Web발신 (광고)본문"))
    }

    @Test
    fun isAdRejectsNullAndEmpty() {
        assertFalse(AdSmsPolicy.isAd(null))
        assertFalse(AdSmsPolicy.isAd(""))
    }

    @Test
    fun isAdRejectsUnrelatedBracketedText() {
        assertFalse(AdSmsPolicy.isAd("인증번호 [123456]를 입력하세요"))
    }

    @Test
    fun snippetKeepsThirtyCharsOnOneLine() {
        val body = "(광고)줄1\n줄2\t탭 " + "가".repeat(40)
        val snippet = AdSmsPolicy.snippet(body)
        assertEquals(30, snippet.length)
        assertTrue(snippet.startsWith("(광고)줄1 줄2 탭 "))
        assertFalse(snippet.contains('\n'))
        assertFalse(snippet.contains('\t'))
        assertEquals("짧은 본문", AdSmsPolicy.snippet("  짧은 본문  "))
        assertEquals("", AdSmsPolicy.snippet(null))
    }
}
