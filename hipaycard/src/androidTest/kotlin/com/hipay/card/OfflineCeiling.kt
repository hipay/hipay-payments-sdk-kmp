package com.hipay.card

import com.hipay.card.validation.CardNetwork

/**
 * Instrumented tests are network-free by contract, but the component asks the account which card
 * networks it may offer as soon as it is composed. Without a stub that reaches the real gateway with
 * the tests' fake credentials: slow, network-dependent, and it makes every test's meaning depend on
 * whether that call failed in time.
 *
 * A permissive ceiling keeps each test asserting what it means to assert — the integrator restriction
 * and the vault verdict — and nothing else. Tests that are ABOUT the ceiling set their own resolver.
 *
 * Applied SYNCHRONOUSLY: an asynchronous stub would still leave the ceiling pending for a coroutine,
 * which legitimately suppresses every network chip, so icon assertions would pass on an idle machine
 * and fail under load.
 */
internal fun HiPayCardEntryController.withOfflineCeiling(
    accepted: Set<CardNetwork> = setOf(
        CardNetwork.VISA,
        CardNetwork.MASTERCARD,
        CardNetwork.AMEX,
        CardNetwork.MAESTRO,
        CardNetwork.CB,
        CardNetwork.BCMC,
    ),
): HiPayCardEntryController = apply { presetAccountNetworks(accepted) }
