package com.pomo.update

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A release asset as exposed by the GitHub API, reduced to the fields we read. */
internal data class ReleaseAsset(val name: String, val downloadUrl: String)

internal data class ReleasePayload(
    val tagName: String?,
    val releaseNotes: String,
    val assets: List<ReleaseAsset>,
    val publishedAt: String? = null,
)

/**
 * Queries the latest stable GitHub release for the Pomo repo and decides whether the
 * installed build is behind it. Manual, one-shot; no caching or background polling.
 */
internal class GithubUpdateChecker(
    private val client: OkHttpClient,
    private val repo: String = DEFAULT_REPO,
) {
    suspend fun check(currentVersionName: String): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            when (val release = fetchRelease("releases/latest")) {
                is FetchReleaseResult.Success ->
                    resolveUpdate(
                        currentVersionName = currentVersionName,
                        tagName = release.payload.tagName,
                        releaseNotes = release.payload.releaseNotes,
                        assets = release.payload.assets,
                    )
                FetchReleaseResult.Offline -> UpdateCheckResult.Offline
                FetchReleaseResult.RateLimited -> UpdateCheckResult.RateLimited
                FetchReleaseResult.NotFound -> UpdateCheckResult.MalformedMetadata
                FetchReleaseResult.MalformedMetadata -> UpdateCheckResult.MalformedMetadata
            }
        }

    suspend fun releaseNotesFor(versionName: String): ReleaseNotesResult =
        withContext(Dispatchers.IO) {
            val tags = listOf("v$versionName", versionName).distinct()
            for (tag in tags) {
                when (val release = fetchRelease("releases/tags/$tag")) {
                    is FetchReleaseResult.Success -> {
                        val tagName = release.payload.tagName ?: return@withContext ReleaseNotesResult.MalformedMetadata
                        val parsedVersion = tagName.trim().removePrefix("v").removePrefix("V")
                        return@withContext ReleaseNotesResult.Found(
                            VersionReleaseNotes(
                                versionName = parsedVersion,
                                releaseNotes = release.payload.releaseNotes,
                            ),
                        )
                    }
                    FetchReleaseResult.NotFound -> continue
                    FetchReleaseResult.Offline -> return@withContext ReleaseNotesResult.Offline
                    FetchReleaseResult.RateLimited -> return@withContext ReleaseNotesResult.RateLimited
                    FetchReleaseResult.MalformedMetadata ->
                        return@withContext ReleaseNotesResult.MalformedMetadata
                }
            }
            ReleaseNotesResult.NotFound
        }

    private fun fetchRelease(path: String): FetchReleaseResult {
        val request =
            Request.Builder()
                .url("https://api.github.com/repos/$repo/$path")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", USER_AGENT)
                .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    return FetchReleaseResult.RateLimited
                }
                if (response.code == 403 &&
                    (
                        response.header("X-RateLimit-Remaining") == "0" ||
                            response.header("Retry-After") != null
                    )
                ) {
                    return FetchReleaseResult.RateLimited
                }
                if (response.code == 404) return FetchReleaseResult.NotFound
                if (!response.isSuccessful) {
                    return FetchReleaseResult.MalformedMetadata
                }
                val body = response.body?.string() ?: return FetchReleaseResult.MalformedMetadata
                parseRelease(body)?.let { payload ->
                    FetchReleaseResult.Success(payload)
                } ?: FetchReleaseResult.MalformedMetadata
            }
        } catch (_: IOException) {
            FetchReleaseResult.Offline
        }
    }

    private fun parseRelease(json: String): ReleasePayload? =
        try {
            parseReleaseObject(JsonParser.parseString(json).asJsonObject)
        } catch (_: Exception) {
            null
        }

    private fun parseReleaseObject(root: com.google.gson.JsonObject): ReleasePayload {
        val tag = root.get("tag_name")?.takeUnless { it.isJsonNull }?.asString
        val notes = root.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val publishedAt = root.get("published_at")?.takeUnless { it.isJsonNull }?.asString
        val assets =
            root.getAsJsonArray("assets")?.mapNotNull { element ->
                val obj = element.asJsonObject
                val name = obj.get("name")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
                val url =
                    obj.get("browser_download_url")?.takeUnless { it.isJsonNull }?.asString
                        ?: return@mapNotNull null
                ReleaseAsset(name, url)
            }.orEmpty()
        return ReleasePayload(tagName = tag, releaseNotes = notes, assets = assets, publishedAt = publishedAt)
    }

    /** Recent releases, newest first, for the changelog screen. */
    suspend fun releases(): ReleasesResult =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url("https://api.github.com/repos/$repo/releases?per_page=10")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", USER_AGENT)
                    .build()

            try {
                client.newCall(request).execute().use { response ->
                    val rateLimited =
                        response.code == 429 ||
                            (
                                response.code == 403 &&
                                    (
                                        response.header("X-RateLimit-Remaining") == "0" ||
                                            response.header("Retry-After") != null
                                    )
                            )
                    if (rateLimited) return@use ReleasesResult.RateLimited
                    if (!response.isSuccessful) return@use ReleasesResult.MalformedMetadata
                    val body = response.body?.string() ?: return@use ReleasesResult.MalformedMetadata
                    val entries =
                        try {
                            JsonParser.parseString(body).asJsonArray.mapNotNull { element ->
                                val payload = parseReleaseObject(element.asJsonObject)
                                val tag = payload.tagName ?: return@mapNotNull null
                                ReleaseEntry(
                                    versionName = tag.trim().removePrefix("v").removePrefix("V"),
                                    releaseNotes = payload.releaseNotes,
                                    publishedAt = payload.publishedAt,
                                )
                            }
                        } catch (_: Exception) {
                            return@use ReleasesResult.MalformedMetadata
                        }
                    if (entries.isEmpty()) ReleasesResult.MalformedMetadata else ReleasesResult.Success(entries)
                }
            } catch (_: IOException) {
                ReleasesResult.Offline
            }
        }

    internal companion object {
        const val DEFAULT_REPO: String = "Snehit70/pomo"
    }
}

private sealed interface FetchReleaseResult {
    data class Success(val payload: ReleasePayload) : FetchReleaseResult

    data object NotFound : FetchReleaseResult

    data object Offline : FetchReleaseResult

    data object RateLimited : FetchReleaseResult

    data object MalformedMetadata : FetchReleaseResult
}

/**
 * Pure decision: given the installed version and the raw release fields, decide the
 * result. Extracted from any I/O so it can be unit tested.
 */
internal fun resolveUpdate(
    currentVersionName: String,
    tagName: String?,
    releaseNotes: String,
    assets: List<ReleaseAsset>,
): UpdateCheckResult {
    val current = SemVer.parseOrNull(currentVersionName) ?: return UpdateCheckResult.MalformedMetadata
    val tag = tagName?.takeIf { it.isNotBlank() } ?: return UpdateCheckResult.MalformedMetadata
    val latest = SemVer.parseOrNull(tag) ?: return UpdateCheckResult.MalformedMetadata

    if (latest <= current) return UpdateCheckResult.UpToDate

    val apk =
        assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return UpdateCheckResult.MissingAsset
    val sha =
        assets.firstOrNull { it.name.endsWith(".apk.sha256", ignoreCase = true) }
            ?: return UpdateCheckResult.MissingAsset

    return UpdateCheckResult.UpdateAvailable(
        LatestRelease(
            versionName = tag.trim().removePrefix("v").removePrefix("V"),
            apkUrl = apk.downloadUrl,
            sha256Url = sha.downloadUrl,
            releaseNotes = releaseNotes,
        ),
    )
}

internal const val USER_AGENT: String = "Pomo-Android-Updater"
