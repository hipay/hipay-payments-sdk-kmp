package com.hipay.core.gateway.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Card-token details echoed on card transactions (null for other products). */
@Serializable
public class PaymentMethod(
    @SerialName("token") public val token: String? = null,
    @SerialName("cardId") public val cardId: String? = null,
    @SerialName("brand") public val brand: String? = null,
    @SerialName("pan") public val pan: String? = null,
    @SerialName("cardHolder") public val cardHolder: String? = null,
    @SerialName("cardExpiryMonth") public val cardExpiryMonth: String? = null,
    @SerialName("cardExpiryYear") public val cardExpiryYear: String? = null,
    @SerialName("issuer") public val issuer: String? = null,
    @SerialName("country") public val country: String? = null,
)
