package com.hipay.core.gateway.model

/**
 * Order operation, serialized as a string VERB on the wire ("Sale" /
 * "Authorization") — real-API verdict 2026-06-12: an integer is rejected with
 * 400 "non alphabetic characters" (legacy mapper
 * `HPFOrderRelatedRequestSerializationMapper.m:78-82`).
 */
public enum class Operation(internal val wireValue: String) {
    SALE("Sale"),
    AUTHORIZATION("Authorization"),
}
