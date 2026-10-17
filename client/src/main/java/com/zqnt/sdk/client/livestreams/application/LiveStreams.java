package com.zqnt.sdk.client.livestreams.application;

import com.zqnt.protos.stream.v3.LiveStream;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Live video of assets. The media server is the source: a stream is listed while something publishes
 * to it, whatever started it.
 */
public interface LiveStreams {

    /**
     * Every stream of the caller's organization that is live right now: the asset publishing it (for a
     * docked aircraft, the aircraft), the media server's path, since when it is received and how many
     * sessions read it.
     *
     * <p>The future fails with a {@link com.zqnt.sdk.client.commands.domains.CommandException} carrying
     * the gRPC status: {@code PERMISSION_DENIED} when the organization's license does not include live
     * streams, {@code UNAVAILABLE} when the platform has no current answer from the media server.</p>
     */
    CompletableFuture<List<LiveStream>> listLiveStreams();
}
