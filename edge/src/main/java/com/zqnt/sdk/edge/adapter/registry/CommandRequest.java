package com.zqnt.sdk.edge.adapter.registry;

import java.util.Map;

/**
 * One command for a registered handler.
 *
 * @param sn                 the asset the command is for
 * @param targetRef          the sub-asset, payload or component it addresses; null for the asset itself
 * @param commandId          the dotted command id, e.g. {@code flight.takeoff}
 * @param params             validated against the command's input schema, integers already converted
 * @param commandExecutionId the platform's id of this run: v3's {@code command_execution_id}, or on v2
 *                           the request's {@code external_id} (e.g. {@code capexec:<execution>:<node>});
 *                           null when the platform sent none. Events of an ACCEPTED command carry it as
 *                           their execution id.
 */
public record CommandRequest(String sn, String targetRef, String commandId, Map<String, Object> params,
		String commandExecutionId) {

	public CommandRequest {
		params = params == null ? Map.of() : params;
	}

	public CommandRequest withParams(Map<String, Object> validated) {
		return new CommandRequest(sn, targetRef, commandId, validated, commandExecutionId);
	}
}
