package org.schabi.newpipe.player.resolver;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;
import static org.schabi.newpipe.player.helper.PlayerDataSource.LIVE_STREAM_EDGE_GAP_MILLIS;

import android.net.Uri;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylistParser;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.manifest.SsManifest;
import com.google.android.exoplayer2.source.smoothstreaming.manifest.SsManifestParser;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.niconico.NiconicoService;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.helper.NonUriHlsPlaylistParserFactory;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.util.StreamTypeUtil;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public interface PlaybackResolver extends Resolver<StreamInfo, MediaSource> {
    String TAG = PlaybackResolver.class.getSimpleName();

    @Nullable
    static MediaSource maybeBuildLiveMediaSource(@NonNull final PlayerDataSource dataSource,
                                                 @NonNull final StreamInfo info) {
        final StreamType streamType = info.getStreamType();
        if (!StreamTypeUtil.isLiveStream(streamType)) {
            return null;
        }

        final StreamInfoTag tag = StreamInfoTag.of(info);
        if (!info.getHlsUrl().isEmpty()) {
            return buildLiveMediaSource(dataSource, info.getHlsUrl(), C.CONTENT_TYPE_HLS, tag);
        } else if (!info.getDashMpdUrl().isEmpty()) {
            return buildLiveMediaSource(
                    dataSource, info.getDashMpdUrl(), C.CONTENT_TYPE_DASH, tag);
        }

        return null;
    }

    @NonNull
    static MediaSource buildLiveMediaSource(@NonNull final PlayerDataSource dataSource,
                                            @NonNull final String sourceUrl,
                                            @C.ContentType final int type,
                                            @NonNull final MediaItemTag metadata) {
        final MediaSource.Factory factory;
        if(sourceUrl.contains("live.nicovideo.jp/watch")){
            factory = dataSource.getNicoLiveHlsMediaSourceFactory(sourceUrl);
            return factory.createMediaSource(
                    new MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(sourceUrl))
                            .setLiveConfiguration(
                                    new MediaItem.LiveConfiguration.Builder()
                                            .setTargetOffsetMs(LIVE_STREAM_EDGE_GAP_MILLIS)
                                            .build())
                            .build()
            );
        }
        switch (type) {
            case C.CONTENT_TYPE_SS:
                factory = dataSource.getLiveSsMediaSourceFactory();
                break;
            case C.CONTENT_TYPE_DASH:
                factory = dataSource.getLiveDashMediaSourceFactory();
                break;
            case C.CONTENT_TYPE_HLS:
                factory = dataSource.getLiveHlsMediaSourceFactory();
                break;
            case C.CONTENT_TYPE_OTHER:
            case C.CONTENT_TYPE_RTSP:
            default:
                throw new IllegalStateException("Unsupported type: " + type);
        }

        return factory.createMediaSource(
                new MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(sourceUrl))
                        .setLiveConfiguration(
                                new MediaItem.LiveConfiguration.Builder()
                                        .setTargetOffsetMs(LIVE_STREAM_EDGE_GAP_MILLIS)
                                        .build())
                        .build());
    }

    @NonNull
    static MediaSource buildMediaSource(@NonNull final PlayerDataSource dataSource,
                                        @NonNull final Stream stream,
                                        @NonNull final StreamInfo streamInfo,
                                        @NonNull final String cacheKey,
                                        @NonNull final MediaItemTag metadata)
            throws IOException {
        StreamingService service = streamInfo.getService();
        if (ServiceList.YouTube.equals(service)) {
            return createYoutubeMediaSource(stream, streamInfo, dataSource, cacheKey, metadata);
        } else if (ServiceList.NicoNico.equals(service)) {
            return createNicoNicoMediaSource(stream, streamInfo, dataSource, cacheKey, metadata);
        } else if (ServiceList.BiliBili.equals(service)) {
            return createBiliBiliMediaSource(stream, streamInfo, dataSource, cacheKey, metadata);
        }

        final DeliveryMethod deliveryMethod = stream.getDeliveryMethod();
        switch (deliveryMethod) {
            case PROGRESSIVE_HTTP:
                return buildProgressiveMediaSource(dataSource, stream, cacheKey, metadata);
            case DASH:
                return buildDashMediaSource(dataSource, stream, cacheKey, metadata);
            case HLS:
                return buildHlsMediaSource(dataSource, stream, cacheKey, metadata);
            case SS:
                return buildSSMediaSource(dataSource, stream, cacheKey, metadata);
            // Torrent streams are not supported by ExoPlayer
            default:
                throw new IllegalArgumentException("Unsupported delivery type: " + deliveryMethod);
        }
    }

    @NonNull
    private static <T extends Stream> ProgressiveMediaSource buildProgressiveMediaSource(
            @NonNull final PlayerDataSource dataSource,
            @NonNull final T stream,
            @NonNull final String cacheKey,
            @NonNull final MediaItemTag metadata) throws IOException {
        final String url = stream.getContent();

        if (isNullOrEmpty(url)) {
            throw new IOException(
                    "Try to generate a progressive media source from an empty string or from a "
                            + "null object");
        } else {
            return dataSource.getProgressiveMediaSourceFactory().createMediaSource(
                    new MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(url))
                            .setCustomCacheKey(cacheKey)
                            .build());
        }
    }

    @NonNull
    private static <T extends Stream> DashMediaSource buildDashMediaSource(
            @NonNull final PlayerDataSource dataSource,
            @NonNull final T stream,
            @NonNull final String cacheKey,
            @NonNull final MediaItemTag metadata) throws IOException {
        final boolean isUrlStream = stream.isUrl();
        if (isUrlStream && isNullOrEmpty(stream.getContent())) {
            throw new IOException("Try to generate a DASH media source from an empty string or "
                    + "from a null object");
        }

        if (isUrlStream) {
            return dataSource.getDashMediaSourceFactory().createMediaSource(
                    new MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.getContent()))
                            .setCustomCacheKey(cacheKey)
                            .build());
        } else {
            String baseUrl = stream.getManifestUrl();
            if (baseUrl == null) {
                baseUrl = "";
            }

            final Uri uri = Uri.parse(baseUrl);

            return dataSource.getDashMediaSourceFactory().createMediaSource(
                    createDashManifest(stream.getContent(), stream),
                    new MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(uri)
                            .setCustomCacheKey(cacheKey)
                            .build());
        }
    }

    @NonNull
    private static <T extends Stream> DashManifest createDashManifest(
            @NonNull final String manifestContent,
            @NonNull final T stream) throws IOException {
        try {
            final ByteArrayInputStream dashManifestInput = new ByteArrayInputStream(
                    manifestContent.getBytes(StandardCharsets.UTF_8));
            String baseUrl = stream.getManifestUrl();
            if (baseUrl == null) {
                baseUrl = "";
            }

            return new DashManifestParser().parse(Uri.parse(baseUrl), dashManifestInput);
        } catch (final IOException e) {
            throw new IOException("Error when parsing manual DASH manifest", e);
        }
    }

    @NonNull
    private static <T extends Stream> HlsMediaSource buildHlsMediaSource(
            @NonNull final PlayerDataSource dataSource,
            @NonNull final T stream,
            @NonNull final String cacheKey,
            @NonNull final MediaItemTag metadata) throws IOException {
        final boolean isUrlStream = stream.isUrl();
        if (isUrlStream && isNullOrEmpty(stream.getContent())) {
            throw new IOException("Try to generate an HLS media source from an empty string or "
                    + "from a null object");
        }

        if (isUrlStream) {
            return dataSource.getHlsMediaSourceFactory(null).createMediaSource(
                    new MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.getContent()))
                            .setCustomCacheKey(cacheKey)
                            .build());
        } else {
            String baseUrl = stream.getManifestUrl();
            if (baseUrl == null) {
                baseUrl = "";
            }

            final Uri uri = Uri.parse(baseUrl);

            final HlsPlaylist hlsPlaylist;
            try {
                final ByteArrayInputStream hlsManifestInput = new ByteArrayInputStream(
                        stream.getContent().getBytes(StandardCharsets.UTF_8));
                hlsPlaylist = new HlsPlaylistParser().parse(uri, hlsManifestInput);
            } catch (final IOException e) {
                throw new IOException("Error when parsing manual HLS manifest", e);
            }

            return dataSource.getHlsMediaSourceFactory(
                            new NonUriHlsPlaylistParserFactory(hlsPlaylist))
                    .createMediaSource(new MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.getContent()))
                            .setCustomCacheKey(cacheKey)
                            .build());
        }
    }

    @NonNull
    private static <T extends Stream> SsMediaSource buildSSMediaSource(
            @NonNull final PlayerDataSource dataSource,
            @NonNull final T stream,
            @NonNull final String cacheKey,
            @NonNull final MediaItemTag metadata) throws IOException {
        final boolean isUrlStream = stream.isUrl();
        if (isUrlStream && isNullOrEmpty(stream.getContent())) {
            throw new IOException("Try to generate an SmoothStreaming media source from an empty "
                    + "string or from a null object");
        }

        if (isUrlStream) {
            return dataSource.getSSMediaSourceFactory().createMediaSource(
                    new MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.getContent()))
                            .setCustomCacheKey(cacheKey)
                            .build());
        } else {
            String baseUrl = stream.getManifestUrl();
            if (baseUrl == null) {
                baseUrl = "";
            }

            final Uri uri = Uri.parse(baseUrl);

            final SsManifest smoothStreamingManifest;
            try {
                final ByteArrayInputStream smoothStreamingManifestInput = new ByteArrayInputStream(
                        stream.getContent().getBytes(StandardCharsets.UTF_8));
                smoothStreamingManifest = new SsManifestParser().parse(uri,
                        smoothStreamingManifestInput);
            } catch (final IOException e) {
                throw new IOException("Error when parsing manual SmoothStreaming manifest", e);
            }

            return dataSource.getSSMediaSourceFactory().createMediaSource(
                    smoothStreamingManifest,
                    new MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(uri)
                            .setCustomCacheKey(cacheKey)
                            .build());
        }
    }

    private static <T extends Stream> MediaSource createYoutubeMediaSource(
            final T stream,
            final StreamInfo streamInfo,
            final PlayerDataSource dataSource,
            final String cacheKey,
            final MediaItemTag metadata) throws IOException {
        if (!(stream instanceof AudioStream || stream instanceof VideoStream)) {
            throw new IOException("Try to generate a DASH manifest of a YouTube "
                    + stream.getClass() + " " + stream.getContent());
        }

        final StreamType streamType = streamInfo.getStreamType();
        if (streamType == StreamType.VIDEO_STREAM) {
            return createYoutubeMediaSourceOfVideoStreamType(dataSource, stream, streamInfo,
                    cacheKey, metadata);
        } else if (streamType == StreamType.POST_LIVE_STREAM) {
            // If the content is not an URL, uses the DASH delivery method and if the stream type
            // of the stream is a post live stream, it means that the content is an ended
            // livestream so we need to generate the manifest corresponding to the content
            // (which is the last segment of the stream)

            try {
                final ItagItem itagItem = Objects.requireNonNull(stream.getItagItem());
                final String manifestString = YoutubePostLiveStreamDvrDashManifestCreator
                        .fromPostLiveStreamDvrStreamingUrl(stream.getContent(),
                                itagItem,
                                itagItem.getTargetDurationSec(),
                                streamInfo.getDuration());
                return buildYoutubeManualDashMediaSource(dataSource,
                        createDashManifest(manifestString, stream), stream, cacheKey,
                        metadata);
            } catch (final CreationException | NullPointerException e) {
                Log.e(TAG, "Error when generating the DASH manifest of YouTube ended live stream",
                        e);
                throw new IOException("Error when generating the DASH manifest of YouTube ended "
                        + "live stream " + stream.getContent(), e);
            }
        } else {
            throw new IllegalArgumentException("DASH manifest generation of YouTube livestreams is "
                    + "not supported");
        }
    }

    private static <T extends Stream> MediaSource createYoutubeMediaSourceOfVideoStreamType(
            @NonNull final PlayerDataSource dataSource,
            @NonNull final T stream,
            @NonNull final StreamInfo streamInfo,
            @NonNull final String cacheKey,
            @NonNull final MediaItemTag metadata) throws IOException {
        final DeliveryMethod deliveryMethod = stream.getDeliveryMethod();
        switch (deliveryMethod) {
            case PROGRESSIVE_HTTP:
                if ((stream instanceof VideoStream && ((VideoStream) stream).isVideoOnly())
                        || stream instanceof AudioStream) {
                    try {
                        final String manifestString = YoutubeProgressiveDashManifestCreator
                                .fromProgressiveStreamingUrl(stream.getContent(),
                                        Objects.requireNonNull(stream.getItagItem()),
                                        streamInfo.getDuration());
                        return buildYoutubeManualDashMediaSource(dataSource,
                                createDashManifest(manifestString, stream), stream, cacheKey,
                                metadata);
                    } catch (final CreationException | IOException | NullPointerException e) {
                        Log.w(TAG, "Error when generating or parsing DASH manifest of "
                                + "YouTube progressive stream, falling back to a "
                                + "ProgressiveMediaSource.", e);
                        return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey,
                                metadata);
                    }
                } else {
                    // Legacy progressive streams, subtitles are handled by
                    // VideoPlaybackResolver
                    return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey,
                            metadata);
                }
            case DASH:
                // If the content is not a URL, uses the DASH delivery method and if the stream
                // type of the stream is a video stream, it means the content is an OTF stream
                // so we need to generate the manifest corresponding to the content (which is
                // the base URL of the OTF stream).

                try {
                    final String manifestString = YoutubeOtfDashManifestCreator
                            .fromOtfStreamingUrl(stream.getContent(),
                                    Objects.requireNonNull(stream.getItagItem()),
                                    streamInfo.getDuration());
                    return buildYoutubeManualDashMediaSource(dataSource,
                            createDashManifest(manifestString, stream), stream, cacheKey,
                            metadata);
                } catch (final CreationException | NullPointerException e) {
                    Log.e(TAG,
                            "Error when generating the DASH manifest of YouTube OTF stream", e);
                    throw new IOException(
                            "Error when generating the DASH manifest of YouTube OTF stream "
                                    + stream.getContent(), e);
                }
            case HLS:
                return dataSource.getYoutubeHlsMediaSourceFactory().createMediaSource(
                        new MediaItem.Builder()
                                .setTag(metadata)
                                .setUri(Uri.parse(stream.getContent()))
                                .setCustomCacheKey(cacheKey)
                                .build());
            default:
                throw new IOException("Unsupported delivery method for YouTube contents: "
                        + deliveryMethod);
        }
    }

    @NonNull
    private static <T extends Stream> DashMediaSource buildYoutubeManualDashMediaSource(
            @NonNull final PlayerDataSource dataSource,
            @NonNull final DashManifest dashManifest,
            @NonNull final T stream,
            @NonNull final String cacheKey,
            @NonNull final MediaItemTag metadata) {
        return dataSource.getYoutubeDashMediaSourceFactory().createMediaSource(dashManifest,
                new MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.getContent()))
                        .setCustomCacheKey(cacheKey)
                        .build());
    }

    @NonNull
    private static <T extends Stream> ProgressiveMediaSource buildYoutubeProgressiveMediaSource(
            @NonNull final PlayerDataSource dataSource,
            @NonNull final T stream,
            @NonNull final String cacheKey,
            @NonNull final MediaItemTag metadata) {
        return dataSource.getYoutubeProgressiveMediaSourceFactory()
                .createMediaSource(new MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.getContent()))
                        .setCustomCacheKey(cacheKey)
                        .build());
    }
    private static <T extends Stream> MediaSource createNicoNicoMediaSource(
            final T stream,
            final StreamInfo streamInfo,
            final PlayerDataSource dataSource,
            final String cacheKey,
            final MediaItemTag metadata) throws IOException{
        String sourceUrl = stream.getContent();
        MediaSource.Factory factory;
        String additionalParam = URLDecoder.decode(sourceUrl.split("cookie=")[1]);
        String cookie = additionalParam.split("&length=")[0];
        String length = additionalParam.split("&length=")[1];
        sourceUrl = sourceUrl.split("#cookie=")[0];
        Uri uri = Uri.parse(sourceUrl);
        factory = dataSource.getNicoMediaSourceFactory(cookie);
        return factory.createMediaSource(
                new MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(uri)
                        .setCustomCacheKey(cacheKey)
                        .build()
        );
    }

    private static <T extends Stream> MediaSource createBiliBiliMediaSource(
            final T stream,
            final StreamInfo streamInfo,
            final PlayerDataSource dataSource,
            final String cacheKey,
            final MediaItemTag metadata) throws IOException{
        final String url = stream.getContent();
        return dataSource.getBiliMediaSourceFactory(streamInfo.getUrl()).createMediaSource(
                new MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(url))
                        .setCustomCacheKey(cacheKey)
                        .build());
    }
}
