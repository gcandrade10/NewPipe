package org.schabi.newpipe.player.mediaitem;

import android.net.Uri;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.MediaMetadata;
import com.google.android.exoplayer2.Player;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Metadata container and accessor used by player internals.
 *
 * This interface ensures consistency of fetching metadata on each stream,
 * which is encapsulated in a {@link MediaItem} and delivered via ExoPlayer's
 * {@link Player.Listener} on event triggers to the downstream users.
 **/
public interface MediaItemTag {

    List<Exception> getErrors();

    int getServiceId();

    String getTitle();

    String getUploaderName();

    long getDurationSeconds();

    String getStreamUrl();

    String getThumbnailUrl();

    String getUploaderUrl();

    StreamType getStreamType();

    @NonNull
    default Optional<StreamInfo> getMaybeStreamInfo() {
        return Optional.empty();
    }

    @NonNull
    default Optional<Quality> getMaybeQuality() {
        return Optional.empty();
    }

    <T> Optional<T> getMaybeExtras(@NonNull Class<T> type);

    <T> MediaItemTag withExtras(@NonNull T extra);

    @NonNull
    static Optional<MediaItemTag> from(@Nullable final MediaItem mediaItem) {
        if (mediaItem == null || mediaItem.localConfiguration == null
                || !(mediaItem.localConfiguration.tag instanceof MediaItemTag)) {
            return Optional.empty();
        }

        return Optional.of((MediaItemTag) mediaItem.localConfiguration.tag);
    }

    @NonNull
    default String makeMediaId() {
        return UUID.randomUUID().toString() + "[" + getTitle() + "]";
    }

    @NonNull
    default MediaItem asMediaItem() {
        Uri uri = Uri.parse(getStreamUrl());
        final MediaMetadata mediaMetadata = new MediaMetadata.Builder()
                .setMediaUri(uri)
                .setArtworkUri(Uri.parse(getThumbnailUrl()))
                .setArtist(getUploaderName())
                .setDescription(getTitle())
                .setDisplayTitle(getTitle())
                .setTitle(getTitle())
                .build();

        return MediaItem.fromUri(uri)
                .buildUpon()
                .setMediaId(makeMediaId())
                .setMediaMetadata(mediaMetadata)
                .setTag(this)
                .build();
    }

    final class Quality {
        @NonNull
        private final List<VideoStream> sortedVideoStreams;
        private final int selectedVideoStreamIndex;

        private Quality(@NonNull final List<VideoStream> sortedVideoStreams,
                        final int selectedVideoStreamIndex) {
            this.sortedVideoStreams = sortedVideoStreams;
            this.selectedVideoStreamIndex = selectedVideoStreamIndex;
        }

        static Quality of(@NonNull final List<VideoStream> sortedVideoStreams,
                          final int selectedVideoStreamIndex) {
            return new Quality(sortedVideoStreams, selectedVideoStreamIndex);
        }

        @NonNull
        public List<VideoStream> getSortedVideoStreams() {
            return sortedVideoStreams;
        }

        public int getSelectedVideoStreamIndex() {
            return selectedVideoStreamIndex;
        }

        @Nullable
        public VideoStream getSelectedVideoStream() {
            return selectedVideoStreamIndex < 0
                    || selectedVideoStreamIndex >= sortedVideoStreams.size()
                    ? null : sortedVideoStreams.get(selectedVideoStreamIndex);
        }
    }
}
