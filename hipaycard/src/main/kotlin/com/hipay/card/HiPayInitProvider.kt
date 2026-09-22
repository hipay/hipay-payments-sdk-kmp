// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Captures the application context at process start, so the card stores work without the component
 * ever being rendered and without the host calling an init of its own — the mechanism WorkManager and
 * ProcessLifecycleOwner use for the same need.
 *
 * It does nothing else: no disk, no network, no work. It is never queried, and every data method
 * below answers empty by contract.
 */
public class HiPayInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        HiPayAppContext.offer(context)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
