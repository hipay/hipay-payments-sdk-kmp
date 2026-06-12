package com.hipay.core.gateway.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Order echo carried by a transaction (REAL camelCase contract). */
@Serializable
public class Order(
    @SerialName("id") public val id: String? = null,
    @SerialName("dateCreated") public val dateCreated: String? = null,
    @SerialName("attempts") public val attempts: String? = null,
    @SerialName("amount") public val amount: String? = null,
    @SerialName("shipping") public val shipping: String? = null,
    @SerialName("tax") public val tax: String? = null,
    @SerialName("decimals") public val decimals: String? = null,
    @SerialName("currency") public val currency: String? = null,
    @SerialName("customerId") public val customerId: String? = null,
    @SerialName("language") public val language: String? = null,
    @SerialName("email") public val email: String? = null,
)
