package com.ontotext.trree.geosparql;

import java.io.Closeable;
import java.util.Iterator;

/**
 * Iterator whose traversal owns a closeable resource, such as a Lucene index reader.
 *
 * <p>Values follow the standard {@link Iterator} contract. Callers close the iterator when traversal completes or
 * terminates early, normally with try-with-resources. This type is plugin-internal despite its cross-package public
 * visibility.
 *
 * @param <T> value returned by {@link #next()}
 */
public interface CloseableIterator<T> extends Iterator<T>, Closeable {
}
