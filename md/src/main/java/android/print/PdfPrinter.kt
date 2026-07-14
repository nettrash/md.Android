/*
 * PdfPrinter.kt
 * md (Android)
 *
 * Deliberately in `package android.print`: PrintDocumentAdapter's
 * LayoutResultCallback / WriteResultCallback constructors are package-
 * private in the SDK stubs, so the only way to drive a print adapter by
 * hand — the long-standing print-to-PDF-without-a-print-dialog pattern,
 * which is what gives the A4 export its line-aware pagination — is from
 * a class inside this package. ART doesn't seal framework packages, so
 * the subclassing is fine at runtime. Only Exporter.renderA4Pdf calls it.
 */

package android.print

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor

object PdfPrinter {

    /** Drive [adapter] through its full lifecycle — onStart, onLayout,
     *  onWrite (all pages), onFinish — into [destination] with the given
     *  [attributes], reporting success to [done] on whatever thread the
     *  print framework calls back on (the caller re-posts as needed).
     *  The caller owns, and closes, [destination]. */
    fun writeToPdf(
        adapter: PrintDocumentAdapter,
        attributes: PrintAttributes,
        destination: ParcelFileDescriptor,
        done: (Boolean) -> Unit,
    ) {
        adapter.onStart()
        adapter.onLayout(
            null,
            attributes,
            null,
            object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                    adapter.onWrite(
                        arrayOf(PageRange.ALL_PAGES),
                        destination,
                        CancellationSignal(),
                        object : PrintDocumentAdapter.WriteResultCallback() {
                            override fun onWriteFinished(pages: Array<out PageRange>?) {
                                adapter.onFinish()
                                done(true)
                            }

                            override fun onWriteFailed(error: CharSequence?) {
                                adapter.onFinish()
                                done(false)
                            }

                            override fun onWriteCancelled() {
                                adapter.onFinish()
                                done(false)
                            }
                        },
                    )
                }

                override fun onLayoutFailed(error: CharSequence?) {
                    adapter.onFinish()
                    done(false)
                }

                override fun onLayoutCancelled() {
                    adapter.onFinish()
                    done(false)
                }
            },
            Bundle(),
        )
    }
}
