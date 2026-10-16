package com.zqnt.sdk.client.streams.application;

import com.zqnt.protos.stream.v3.LiveStream;
import com.zqnt.sdk.client.commands.domains.CommandException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Live video of assets, as the media server sees it: a stream is listed while something publishes to it. */
public interface LiveStreams {

    /**
     * Every stream of the caller's organization that is live right now, with its asset, media server
     * path, start time and number of viewers. Fails with a {@link CommandException}; a retryable
     * {@code SERVICE} one when the platform has no current answer from the media server.
     */
    CompletableFuture<List<LiveStream>> listLiveStreams();
}
