package org.schabi.newpipe.player.resolver;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;

import org.schabi.newpipe.database.download.entry.DownloadEntry;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.local.download.DownloadRecordManager;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.util.ListHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.android.exoplayer2.C.TIME_UNSET;

public class VideoPlaybackResolver implements PlaybackResolver {
    @NonNull
    private final Context context;
    @NonNull
    private final PlayerDataSource dataSource;
    @NonNull
    private final QualityResolver qualityResolver;
    private SourceType streamSourceType;

    @Nullable
    private String playbackQuality;

    public enum SourceType {
        LIVE_STREAM,
        VIDEO_WITH_SEPARATED_AUDIO,
        VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
    }

    public VideoPlaybackResolver(@NonNull final Context context,
                                 @NonNull final PlayerDataSource dataSource,
                                 @NonNull final QualityResolver qualityResolver) {
        this.context = context;
        this.dataSource = dataSource;
        this.qualityResolver = qualityResolver;
    }

    @Override
    @Nullable
    public MediaSource resolve(@NonNull final StreamInfo info) {
        final MediaSource liveSource = maybeBuildLiveMediaSource(dataSource, info);
        if (liveSource != null) {
            streamSourceType = SourceType.LIVE_STREAM;
            return liveSource;
        }

        final List<MediaSource> mediaSources = new ArrayList<>();

        // Create video stream source
        final List<VideoStream> videos = ListHelper.getSortedStreamVideosList(context,
                info.getVideoStreams(), info.getVideoOnlyStreams(), false, true);
        final int index;
        if (videos.isEmpty()) {
            index = -1;
        } else if (playbackQuality == null) {
            index = qualityResolver.getDefaultResolutionIndex(videos);
        } else {
            index = qualityResolver.getOverrideResolutionIndex(videos, getPlaybackQuality());
        }
        final MediaItemTag tag = StreamInfoTag.of(info, videos, index);
        @Nullable final VideoStream video = tag.getMaybeQuality()
                .map(MediaItemTag.Quality::getSelectedVideoStream)
                .orElse(null);

        if (video != null) {
            DownloadRecordManager d = new DownloadRecordManager(context);

            DownloadEntry downloadEntry = d.getUri(info.getId()).blockingGet();
            String uri = null;
            if (downloadEntry != null) {
                uri = downloadEntry.getUriValue();
            }
            final MediaSource streamSource = buildMediaSource(dataSource, video.getUrl(),
                    PlayerHelper.cacheKeyOf(info, video),
                    MediaFormat.getSuffixById(video.getFormatId()), tag, uri);
            mediaSources.add(streamSource);
        }

        // Create optional audio stream source
        final List<AudioStream> audioStreams = info.getAudioStreams();
        final AudioStream audio = audioStreams.isEmpty() ? null : audioStreams.get(
                ListHelper.getDefaultAudioFormat(context, audioStreams));
        // Use the audio stream if there is no video stream, or
        // Merge with audio stream in case if video does not contain audio
        if (audio != null && (video == null || video.isVideoOnly)) {
            DownloadRecordManager d = new DownloadRecordManager(context);

            DownloadEntry downloadEntry = d.getUri(info.getId()).blockingGet();
            String uri = null;
            if (downloadEntry != null) {
                uri = downloadEntry.getUriValue();
            }
            final MediaSource audioSource = buildMediaSource(dataSource, audio.getUrl(),
                    PlayerHelper.cacheKeyOf(info, audio),
                    MediaFormat.getSuffixById(audio.getFormatId()), tag, uri);
            mediaSources.add(audioSource);
            streamSourceType = SourceType.VIDEO_WITH_SEPARATED_AUDIO;
        } else {
            streamSourceType = SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY;
        }

        // If there is no audio or video sources, then this media source cannot be played back
        if (mediaSources.isEmpty()) {
            return null;
        }
        // Below are auxiliary media sources

        // Create subtitle sources
        if (info.getSubtitles() != null) {
            for (final SubtitlesStream subtitle : info.getSubtitles()) {
                final String mimeType = PlayerHelper.subtitleMimeTypesOf(subtitle.getFormat());
                if (mimeType == null) {
                    continue;
                }
                final @C.RoleFlags int textRoleFlag = subtitle.isAutoGenerated()
                        ? C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
                        : C.ROLE_FLAG_CAPTION;
                final MediaItem.SubtitleConfiguration textMediaItem =
                        new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.getUrl()))
                                .setMimeType(mimeType)
                                .setRoleFlags(textRoleFlag)
                                .setLanguage(PlayerHelper.captionLanguageOf(context, subtitle))
                                .build();
                final MediaSource textSource = dataSource
                        .getSampleMediaSourceFactory()
                        .createMediaSource(textMediaItem, TIME_UNSET);
                mediaSources.add(textSource);
            }
        }

        if (mediaSources.size() == 1) {
            return mediaSources.get(0);
        } else {
            return new MergingMediaSource(mediaSources.toArray(
                    new MediaSource[0]));
        }
    }

    /**
     * Returns the last resolved {@link StreamInfo}'s {@link SourceType source type}.
     *
     * @return {@link Optional#empty()} if nothing was resolved, otherwise the {@link SourceType}
     * of the last resolved {@link StreamInfo} inside an {@link Optional}
     */
    public Optional<SourceType> getStreamSourceType() {
        return Optional.ofNullable(streamSourceType);
    }

    @Nullable
    public String getPlaybackQuality() {
        return playbackQuality;
    }

    public void setPlaybackQuality(@Nullable final String playbackQuality) {
        this.playbackQuality = playbackQuality;
    }

    public interface QualityResolver {
        int getDefaultResolutionIndex(List<VideoStream> sortedVideos);

        int getOverrideResolutionIndex(List<VideoStream> sortedVideos, String playbackQuality);
    }
}
