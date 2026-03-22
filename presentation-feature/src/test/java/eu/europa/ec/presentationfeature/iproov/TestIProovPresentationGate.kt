/*
 * Copyright (c) 2025 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */
package eu.europa.ec.presentationfeature.iproov

import junit.framework.TestCase.assertEquals
import org.junit.Test

class TestIProovPresentationGate {

    @Test
    fun `buildIProovCallbackUrl returns the wallet callback uri`() {
        assertEquals("eudi-wallet://iproov", buildIProovCallbackUrl())
    }

    @Test
    fun `parseIProovCallbackUri ignores unrelated deep links`() {
        val result = parseIProovCallback(
            callbackUrl = "openid4vp://request",
            expectedSession = "session-123"
        )

        assertEquals(ParsedIProovCallback.Ignored, result)
    }

    @Test
    fun `parseIProovCallbackUri fails when the session does not match`() {
        val result = parseIProovCallback(
            callbackUrl = "eudi-wallet://iproov?session=session-999&passed=true",
            expectedSession = "session-123"
        )

        assertEquals(
            ParsedIProovCallback.Failure("The iProov callback did not match the active session."),
            result
        )
    }

    @Test
    fun `parseIProovCallbackUri returns passed when the session matches`() {
        val result = parseIProovCallback(
            callbackUrl = "eudi-wallet://iproov?session=session-123&passed=true",
            expectedSession = "session-123"
        )

        assertEquals(ParsedIProovCallback.Passed("session-123"), result)
    }
}
