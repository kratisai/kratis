package com.kratisai.controlplane.api.wsdto;

import com.kratisai.controlplane.api.wsprotocol.Direction;
import com.kratisai.controlplane.api.wsprotocol.MessageKind;

/**
 * Sealed set of every JSON-RPC request/notification payload in the Kratis protocol. Each record
 * combines the wire method name and its params in a single declaration: the envelope derives the
 * wire {@code method} from the payload, so untyped or mismatched method/params pairs do not
 * compile.
 *
 * <p>Message kind and direction are encoded by the sealed sub-hierarchy ({@link ClientRpcPayload},
 * {@link EnvironmentRpcPayload} and its sub-interfaces), keeping per-record boilerplate to the
 * method name only.
 */
public sealed interface RpcPayload permits ClientRpcPayload, EnvironmentRpcPayload, RpcPayload.NoParamsPayload {

    String method();

    MessageKind messageKind();

    Direction direction();

    /**
     * Marker for payloads that carry no params on the wire (e.g. {@code ping}). The envelope omits
     * the {@code params} member for these.
     */
    non-sealed interface NoParamsPayload extends RpcPayload {}
}
