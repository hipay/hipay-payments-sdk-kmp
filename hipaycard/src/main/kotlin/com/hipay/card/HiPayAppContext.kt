// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card

import android.content.Context

/**
 * The application [Context] the card stores need, held for the life of the process.
 *
 * Storing needs a Context; presenting a 3DS challenge needs an Activity. Deriving the first from the
 * second made every stored feature depend on the component being on screen — which a headless host
 * never puts there, and which a component leaving the composition took away again. This holds the
 * application Context instead, filled by [HiPayInitProvider] at process start and by any explicit
 * binding, whichever comes first.
 *
 * Only ever the application Context: it is a process singleton, so keeping it leaks nothing, whereas
 * an Activity kept here would.
 */
internal object HiPayAppContext {
    @Volatile
    private var app: Context? = null

    /** Sticky: the first Context seen wins, and a component leaving the screen never clears it. */
    fun offer(context: Context?) {
        if (app == null) app = context?.applicationContext
    }

    fun get(): Context? = app
}
