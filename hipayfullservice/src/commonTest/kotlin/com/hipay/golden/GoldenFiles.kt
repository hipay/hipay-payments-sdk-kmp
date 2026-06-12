package com.hipay.golden

// MIRROR of hipayfullservice/src/commonTest/resources/golden/*.json — the .json
// files are the human-readable source of truth (committed, diffable); these
// constants exist because Kotlin/Native test binaries cannot read classpath
// resources. If you change one side, change the other (the JSON shapes come
// from REAL stage traffic captured on 2026-06-12 — see story 2.2).

internal val GOLDEN_TOKEN_CREATE_REQUEST: String = """
{
  "card_number": "4111111111111111",
  "card_expiry_month": "12",
  "card_expiry_year": "2026",
  "card_holder": "Test",
  "cvc": "123",
  "multi_use": "0"
}
""".trimIndent()

internal val GOLDEN_TOKEN_CREATE_RESPONSE: String = """
{
  "token": "f0e1d2c3b4a5968778695a4b3c2d1e0ff0e1d2c3b4a5968778695a4b3c2d1e0f",
  "request_id": "0",
  "card_id": "00000000-0000-4000-8000-000000000001",
  "multi_use": 0,
  "brand": "VISA",
  "pan": "411111xxxxxx1111",
  "card_holder": "Test",
  "card_expiry_month": "12",
  "card_expiry_year": "2026",
  "issuer": "CONOTOXIA SP. Z O.O",
  "country": "PL",
  "card_type": "DEBIT",
  "card_category": "CLASSIC",
  "forbidden_issuer_country": false
}
""".trimIndent()

internal val GOLDEN_ORDER_REQUEST: String = """
{
  "orderid": "TEST-ORDER-1",
  "payment_product": "visa",
  "operation": "Sale",
  "amount": "1.00",
  "currency": "EUR",
  "description": "Test order",
  "language": "fr_FR",
  "accept_url": "hipaydemo://hipay-fullservice/gateway/orders/TEST-ORDER-1/accept",
  "decline_url": "hipaydemo://hipay-fullservice/gateway/orders/TEST-ORDER-1/decline",
  "pending_url": "hipaydemo://hipay-fullservice/gateway/orders/TEST-ORDER-1/pending",
  "exception_url": "hipaydemo://hipay-fullservice/gateway/orders/TEST-ORDER-1/exception",
  "cancel_url": "hipaydemo://hipay-fullservice/gateway/orders/TEST-ORDER-1/cancel",
  "cardtoken": "f0e1d2c3b4a5968778695a4b3c2d1e0ff0e1d2c3b4a5968778695a4b3c2d1e0f",
  "eci": "7",
  "authentication_indicator": "0"
}
""".trimIndent()

internal val GOLDEN_ORDER_RESPONSE: String = """
{
  "state": "completed",
  "reason": "",
  "forwardUrl": "",
  "test": "true",
  "mid": "00000000000",
  "attemptId": "1",
  "authorizationCode": "0000000000",
  "transactionReference": "800000000001",
  "referenceToPay": "",
  "dateCreated": "2026-06-12T00:00:00+0000",
  "dateUpdated": "2026-06-12T00:00:10+0000",
  "dateAuthorized": "2026-06-12T00:00:08+0000",
  "status": "118",
  "message": "Captured",
  "authorizedAmount": "1.00",
  "capturedAmount": "1.00",
  "refundedAmount": "0.00",
  "creditedAmount": "0.00",
  "decimals": "2",
  "currency": "EUR",
  "ipAddress": "0.0.0.0",
  "ipCountry": "",
  "deviceId": "",
  "cdata1": "",
  "cdata2": "",
  "cdata3": "",
  "cdata4": "",
  "cdata5": "",
  "cdata6": "",
  "cdata7": "",
  "cdata8": "",
  "cdata9": "",
  "cdata10": "",
  "avsResult": "",
  "eci": "7",
  "paymentProduct": "visa",
  "paymentMethod": {
    "token": "f0e1d2c3b4a5968778695a4b3c2d1e0ff0e1d2c3b4a5968778695a4b3c2d1e0f",
    "cardId": "00000000-0000-4000-8000-000000000001",
    "brand": "VISA",
    "pan": "411111******1111",
    "cardHolder": "TEST",
    "cardExpiryMonth": "12",
    "cardExpiryYear": "2026",
    "issuer": "CONOTOXIA SP. Z O.O",
    "country": "PL"
  },
  "threeDSecure": {
    "eci": "5",
    "authenticationStatus": "Y",
    "authenticationMessage": "Authentication Successful",
    "authenticationToken": "",
    "xid": ""
  },
  "fraudScreening": {
    "scoring": "0",
    "result": "ACCEPTED",
    "review": ""
  },
  "order": {
    "id": "TEST-ORDER-1",
    "dateCreated": "2026-06-12T00:00:00+0000",
    "attempts": "1",
    "amount": "1.00",
    "shipping": "0.00",
    "tax": "0.00",
    "decimals": "2",
    "currency": "EUR",
    "customerId": "",
    "language": "fr_FR",
    "email": ""
  },
  "debitAgreement": {
    "id": "",
    "status": ""
  }
}
""".trimIndent()
