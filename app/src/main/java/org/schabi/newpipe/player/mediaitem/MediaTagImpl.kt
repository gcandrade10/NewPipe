package org.schabi.newpipe.player.mediaitem

import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Optional


class MediaTagImpl(
    private val streamInfo: Optional<StreamInfo>,
    private val pTitle: String,
    private val pUploaderName: String,
    private val pThumbnailUrl: String
) : MediaItemTag {
    override fun getMaybeStreamInfo(): Optional<StreamInfo> {
        return streamInfo
    }

    private val extras: Any? = null
    override fun getErrors(): MutableList<Exception> = mutableListOf()

    override fun getServiceId(): Int = -1

    override fun getTitle(): String {
        return pTitle
    }

    override fun getUploaderName(): String {
        return pUploaderName
    }

    override fun getDurationSeconds(): Long = 0L

    override fun getStreamUrl(): String = ""

    override fun getThumbnailUrl(): String {
        return pThumbnailUrl
    }

    override fun getUploaderUrl(): String = ""

    override fun getStreamType(): StreamType = StreamType.AUDIO_STREAM

    override fun <T> getMaybeExtras(type: Class<T>): Optional<T>? {
        return Optional.ofNullable<Any>(extras).map<T> { obj: Any? ->
            type.cast(
                obj
            )
        }
    }

    override fun <T : Any?> withExtras(extra: T): MediaItemTag {
        return MediaTagImpl(Optional.empty(),"withExtras", "withExtras", "withExtras")
    }

//    override fun <T : Any?> withExtras(extra: T & Any): MediaItemTag {
//        return MediaTagImpl("withExtras", "withExtras", "withExtras")
//    }
}