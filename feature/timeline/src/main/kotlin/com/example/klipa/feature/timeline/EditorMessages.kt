package com.example.klipa.feature.timeline

import android.content.res.Resources
import com.example.klipa.core.edit.EditRejectedException.Reason

/** The user-facing text for [message]. */
fun EditorMessage.describe(resources: Resources): String = when (this) {
    is EditorMessage.Rejected -> resources.getString(
        when (reason) {
            Reason.NoAudioToDetach -> R.string.reason_no_audio
            Reason.TrackLocked -> R.string.reason_track_locked
            Reason.AlreadyLinked -> R.string.reason_already_linked
            Reason.InvalidSourceRange -> R.string.reason_invalid_range
            Reason.IncompatibleTrack -> R.string.reason_incompatible_track
            Reason.TouchesLockedTrack -> R.string.reason_locked_track
            Reason.AssetInUse -> R.string.reason_asset_in_use
            Reason.MagneticDeleteDesync -> R.string.reason_magnetic_desync
        },
    )
    EditorMessage.InvalidProject -> resources.getString(R.string.message_invalid_project)
    is EditorMessage.ImportFailed -> resources.getQuantityString(R.plurals.message_import_failed, count, count)
}
