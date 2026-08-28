package com.hipay.core.gateway.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 3-D Secure enrollment/authentication block of a transaction (FR13). */
@Serializable
public class ThreeDSecure(
    @SerialName("eci") public val eci: String? = null,
    @SerialName("enrollmentStatus") public val enrollmentStatus: String? = null,
    @SerialName("enrollmentMessage") public val enrollmentMessage: String? = null,
    @SerialName("authenticationStatus") public val authenticationStatus: String? = null,
    @SerialName("authenticationMessage") public val authenticationMessage: String? = null,
    @SerialName("authenticationToken") public val authenticationToken: String? = null,
    @SerialName("xid") public val xid: String? = null,
)
