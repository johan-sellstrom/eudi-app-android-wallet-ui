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

import android.net.Uri
import eu.europa.ec.presentationfeature.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class IProovPreparationResult {
    data object Disabled : IProovPreparationResult()
    data class Launch(val url: Uri) : IProovPreparationResult()
    data class Failure(val error: String) : IProovPreparationResult()
}

sealed class IProovCallbackResult {
    data object Ignored : IProovCallbackResult()
    data object Passed : IProovCallbackResult()
    data class Failure(val error: String) : IProovCallbackResult()
}

@Single
class IProovPresentationGate(
    private val json: Json,
) {
    private var pendingSession: String? = null

    suspend fun prepareForPresentation(): IProovPreparationResult = withContext(Dispatchers.IO) {
        if (!BuildConfig.IPROOV_GATE_ENABLED) {
            return@withContext IProovPreparationResult.Disabled
        }

        return@withContext runCatching {
            val callbackUrl = buildIProovCallbackUrl()
            val response = requestJson<MobileClaimResponse>(
                url = "${issuerBaseUrl()}/iproov/mobile/claim",
                method = "POST",
                body = json.encodeToString(
                    MobileClaimRequest(
                        callbackUrl = callbackUrl
                    )
                )
            )

            pendingSession = response.session
            IProovPreparationResult.Launch(Uri.parse(response.launchUrl))
        }.getOrElse { error ->
            pendingSession = null
            IProovPreparationResult.Failure(error.message ?: DEFAULT_GATE_FAILURE)
        }
    }

    suspend fun resolveCallback(uri: Uri): IProovCallbackResult = withContext(Dispatchers.IO) {
        when (val callback = parseIProovCallbackUri(uri, pendingSession)) {
            is ParsedIProovCallback.Ignored -> IProovCallbackResult.Ignored
            is ParsedIProovCallback.Failure -> {
                pendingSession = null
                IProovCallbackResult.Failure(callback.error)
            }

            is ParsedIProovCallback.Passed -> {
                runCatching {
                    val response = requestJson<SessionStatusResponse>(
                        url = "${issuerBaseUrl()}/iproov/session/${callback.session}",
                        method = "GET"
                    )

                    pendingSession = null
                    if (response.passed) {
                        IProovCallbackResult.Passed
                    } else {
                        IProovCallbackResult.Failure(
                            response.reason?.takeIf { it.isNotBlank() } ?: DEFAULT_GATE_FAILURE
                        )
                    }
                }.getOrElse { error ->
                    pendingSession = null
                    IProovCallbackResult.Failure(error.message ?: DEFAULT_GATE_FAILURE)
                }
            }
        }
    }

    private fun issuerBaseUrl(): String {
        val baseUrl = BuildConfig.IPROOV_ISSUER_BASE_URL.trim().trimEnd('/')
        require(baseUrl.isNotEmpty()) {
            "IProov is enabled, but IPROOV_ISSUER_BASE_URL is empty."
        }
        return baseUrl
    }

    private inline fun <reified T> requestJson(
        url: String,
        method: String,
        body: String? = null,
    ): T {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("content-type", "application/json")
            }
        }

        return try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val payload = readBody(connection, status)
            if (status !in 200..299) {
                val errorBody = payload?.let {
                    runCatching { json.decodeFromString<ErrorResponse>(it) }.getOrNull()
                }
                val message = errorBody?.message
                    ?: errorBody?.reason
                    ?: errorBody?.error
                    ?: "iProov request failed with status $status"
                throw IllegalStateException(message)
            }

            json.decodeFromString<T>(payload.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, status: Int): String? {
        val stream: InputStream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: return null
        }

        return stream.bufferedReader().use { it.readText() }
    }
}

internal sealed class ParsedIProovCallback {
    data object Ignored : ParsedIProovCallback()
    data class Passed(val session: String) : ParsedIProovCallback()
    data class Failure(val error: String) : ParsedIProovCallback()
}

internal fun buildIProovCallbackUrl(): String {
    val scheme = Uri.parse(BuildConfig.DEEPLINK).scheme ?: "eudi-wallet"
    return "$scheme://iproov"
}

internal fun parseIProovCallbackUri(
    uri: Uri,
    expectedSession: String?,
): ParsedIProovCallback {
    val callbackScheme = Uri.parse(BuildConfig.DEEPLINK).scheme ?: "eudi-wallet"
    if (uri.scheme != callbackScheme || uri.host != "iproov") {
        return ParsedIProovCallback.Ignored
    }

    val session = uri.getQueryParameter("session").orEmpty().trim()
    if (session.isEmpty()) {
        return ParsedIProovCallback.Failure("The iProov callback is missing the session id.")
    }

    if (expectedSession != null && session != expectedSession) {
        return ParsedIProovCallback.Failure("The iProov callback did not match the active session.")
    }

    val passed = uri.getQueryParameter("passed").equals("true", ignoreCase = true)
    if (!passed) {
        val reason = uri.getQueryParameter("reason").orEmpty().trim()
        return ParsedIProovCallback.Failure(reason.ifEmpty { DEFAULT_GATE_FAILURE })
    }

    return ParsedIProovCallback.Passed(session)
}

@Serializable
private data class MobileClaimRequest(
    @SerialName("callback_url")
    val callbackUrl: String,
)

@Serializable
private data class MobileClaimResponse(
    val session: String,
    @SerialName("launchUrl")
    val launchUrl: String,
)

@Serializable
private data class SessionStatusResponse(
    val passed: Boolean = false,
    val reason: String? = null,
)

@Serializable
private data class ErrorResponse(
    val error: String? = null,
    val message: String? = null,
    val reason: String? = null,
)

private const val DEFAULT_GATE_FAILURE = "Complete the iProov ceremony before sharing the presentation."
