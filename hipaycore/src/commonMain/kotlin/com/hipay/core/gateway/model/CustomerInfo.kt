package com.hipay.core.gateway.model

/**
 * Optional customer / shipping information attached to an order. Wire keys
 * mirror the legacy mappers (`HPFCustomerInfoRequestSerializationMapper` /
 * `HPFPersonalInfoRequestSerializationMapper`): customer fields are merged
 * FLAT into the order request; as a shipping address the personal-info subset
 * is prefixed `shipto_` (email/phone are customer-only).
 */
public class CustomerInfo(
    public val firstName: String? = null,
    public val lastName: String? = null,
    public val streetAddress: String? = null,
    public val streetAddress2: String? = null,
    public val recipientInfo: String? = null,
    public val city: String? = null,
    public val state: String? = null,
    public val zipCode: String? = null,
    public val country: String? = null,
    public val email: String? = null,
    public val phone: String? = null,
) {
    internal fun toFields(prefix: String = "", personalInfoOnly: Boolean = false): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        fun put(key: String, value: String?) {
            if (value != null) fields[prefix + key] = value
        }
        put("firstname", firstName)
        put("lastname", lastName)
        put("streetaddress", streetAddress)
        put("streetaddress2", streetAddress2)
        put("recipientinfo", recipientInfo)
        put("city", city)
        put("state", state)
        put("zipcode", zipCode)
        put("country", country)
        if (!personalInfoOnly) {
            put("email", email)
            put("phone", phone)
        }
        return fields
    }
}
