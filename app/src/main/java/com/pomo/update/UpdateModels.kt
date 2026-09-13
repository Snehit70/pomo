package com.pomo.update

import java.io.File

/** The newest stable GitHub release, reduced to what the updater needs. */
internal data class LatestRelease(
    /** Tag with any leading `v` stripped, e.g. "1.25.0". */
    val versionName: String,
    /** `browser_download_url` of the single `.apk` asset. */
    val apkUrl: String,
    /** `browser_download_url` of the companion `.apk.sha256` asset, if present. */
    val sha256Url: String?,
    /** Release notes from the GitHub release body (may be blank). */
    val releaseNotes: String,
)

/** Release notes for one specific published app version. */
internal data class VersionReleaseNotes(
    /** Tag with any leading `v` stripped, e.g. "2.0.0". */
    val versionName: String,
    /** GitHub release body. May be blank if the release was published without notes. */
    val releaseNotes: String,
)

/** Outcome of a manual "Check for updates" request. */
internal sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: LatestRelease) : UpdateCheckResult

    data object UpToDate : UpdateCheckResult

    data object Offline : UpdateCheckResult

    data object RateLimited : UpdateCheckResult

    /** Release JSON was unreadable, or the tag was not a comparable version. */
    data object MalformedMetadata : UpdateCheckResult

    /** A release exists but carries no installable `.apk` asset. */
    data object MissingAsset : UpdateCheckResult
}

/** Outcome of fetching release notes for an already-installed app version. */
internal sealed interface ReleaseNotesResult {
    data class Found(val release: VersionReleaseNotes) : ReleaseNotesResult

    data object NotFound : ReleaseNotesResult

    data object Offline : ReleaseNotesResult

    data object RateLimited : ReleaseNotesResult

    data object MalformedMetadata : ReleaseNotesResult
}

/** Outcome of downloading + verifying the release APK. */
internal sealed interface DownloadOutcome {
    data class Ready(val apk: File) : DownloadOutcome

    data object Offline : DownloadOutcome

    /** sha256 of the download did not match the published checksum. */
    data object Corrupt : DownloadOutcome

    data object Failed : DownloadOutcome
}

internal sealed interface ReleasesResult {
    data class Success(val releases: List<ReleaseEntry>) : ReleasesResult

    data object Offline : ReleasesResult

    data object RateLimited : ReleasesResult

    data object MalformedMetadata : ReleasesResult
}
