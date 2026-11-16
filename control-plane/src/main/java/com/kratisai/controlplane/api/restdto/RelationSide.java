package com.kratisai.controlplane.api.restdto;

/** Which side of a directional relation the current repository occupies. */
public enum RelationSide {
    UPSTREAM,
    DOWNSTREAM,
    BIDIRECTIONAL,
    UNKNOWN
}
