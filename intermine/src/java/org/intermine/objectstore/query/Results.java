package org.flymine.objectstore.query;

/*
 * Copyright (C) 2002-2003 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import org.apache.log4j.Logger;
import java.util.List;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Iterator;

import org.flymine.FlyMineException;
import org.flymine.objectstore.ObjectStore;
import org.flymine.objectstore.ObjectStoreException;
import org.flymine.objectstore.ObjectStoreLimitReachedException;
import org.flymine.util.CacheMap;

/**
 * Results representation as a List of ResultRows
 * Extending AbstractList requires implementation of get(int) and size()
 * In addition subList(int, int) overrides AbstractList implementation for efficiency
 *
 * @author Mark Woodbridge
 * @author Richard Smith
 * @author Matthew Wakeling
 */
public class Results extends AbstractList
{
    protected Query query;
    protected ObjectStore os;
    protected int minSize = 0;
    // TODO: update this to use ObjectStore.getMaxRows().
    protected int maxSize = Integer.MAX_VALUE;
    // -1 stands for "not estimated yet"
    protected int estimatedSize = -1;
    protected int originalMaxSize = maxSize;
    protected int batchSize = 100;
    protected boolean initialised = false;
    private boolean optimise = true;

    // Some prefetch stuff.
    protected int lastGet = -1;
    protected int sequential = 0;
    private static final int PREFETCH_SEQUENTIAL_THRESHOLD = 6;
    // Basically, this keeps a tally of how many times in a row accesses have been sequential.
    // If sequential gets above a PREFETCH_SEQUENTIAL_THRESHOLD, then we prefetch the batch after
    // the one we are currently using.

    protected int lastGetAtGetInfoBatch = -1;
    protected ResultsInfo info;

    protected static final Logger LOG = Logger.getLogger(Results.class);

    // A map of batch number against a List of ResultsRows
    protected Map batches = Collections.synchronizedMap(new CacheMap());

    /**
     * No argument constructor for testing purposes
     *
     */
    protected Results() {
    }

    /**
     * Constructor for a Results object
     *
     * @param query the Query that produces this Results
     * @param os the ObjectStore that can be used to get results rows from
     */
     public Results(Query query, ObjectStore os) {
         if (query == null) {
             throw new NullPointerException("query must not be null");
         }

         if (os == null) {
             throw new NullPointerException("os must not be null");
         }

         this.query = query;
         this.os = os;
     }

    /**
     * Sets this Results object to bypass the optimiser.
     */
    public void setNoOptimise() {
        optimise = false;
    }

    /**
     * Get the Query that produced this Results object
     *
     * @return the Query that produced this Results object
     */
    public Query getQuery() {
        return query;
    }

    /**
     * Return the batches retrieved for this Results object
     * @return a map from batch number to items
     */
    protected Map getBatches() {
        return batches;
    }

    /**
     * Returns a range of rows of results. Will fetch batches from the
     * underlying ObjectStore if necessary.
     *
     * @param start the start index
     * @param end the end index
     * @return the relevant ResultRows as a List
     * @throws ObjectStoreException if an error occurs in the underlying ObjectStore
     * @throws IndexOutOfBoundsException if end is beyond the number of rows in the results
     * @throws IllegalArgumentException if start &gt; end
     * @throws FlyMineException if an error occurs promoting proxies
     */
    public List range(int start, int end) throws ObjectStoreException, FlyMineException {
        if (start > end) {
            throw new IllegalArgumentException();
        }

        int startBatch = getBatchNoForRow(start);
        int endBatch = getBatchNoForRow(end);

        // If we know the size of the results (ie. have had a last partial batch), check that
        // the end is within range
        if (end >= maxSize) {
            throw new IndexOutOfBoundsException("End = " + end + ", size = " + maxSize);
        }

        if (start - 1 == lastGet) {
            sequential += end - start + 1;
            //LOG.debug("This access sequential = " + sequential
            //        + "                            Result " + query.hashCode()
            //        + "         access " + start + " - " + end);
        } else {
            sequential = 0;
            //LOG.debug("This access not sequential                            Result "
            //        + query.hashCode() + "         access " + start + " - " + end);
        }
        if ((sequential > PREFETCH_SEQUENTIAL_THRESHOLD) && (getBatchNoForRow(maxSize) > endBatch)
                && (!batches.containsKey(new Integer(endBatch + 1)))) {
            PrefetchManager.addRequest(this, endBatch + 1);
        }
        lastGet = end;
        /*
        // Do the loop in reverse, so that we get IndexOutOfBoundsException first thing if we are
        // out of range
        for (int i = endBatch; i >= startBatch; i--) {
            // Only one thread does this test at a time to save on calls to ObjectStore
            synchronized (batches) {
                if (!batches.containsKey(new Integer(i))) {
                    fetchBatchFromObjectStore(i);
                }
            }
        }
        */
        return localRange(start, end);
    }

    /**
     * Returns a combined list of objects from the batches Map
     *
     * @param start the start row
     * @param end the end row
     * @return a List of ResultsRows made up of the ResultsRows in the individual batches
     * @throws FlyMineException if an error occurs promoting proxies
     * @throws ObjectStoreException if an error occurs in the underlying ObjectStore
     * @throws IndexOutOfBoundsException if the batch is off the end of the results
     */
    protected List localRange(int start, int end) throws FlyMineException, ObjectStoreException {
        List ret = new ArrayList();
        int startBatch = getBatchNoForRow(start);
        int endBatch = getBatchNoForRow(end);

        for (int i = startBatch; i <= endBatch; i++) {
            List rows = getRowsFromBatch(i, start, end);
            ret.addAll(getRowsFromBatch(i, start, end));
        }
        return ret;
    }

    /**
     * Gets a range of rows from within a batch
     *
     * @param batchNo the batch number
     * @param start the row to start from (based on total rows)
     * @param end the row to end at (based on total rows) - returned List includes this row
     * @throws ObjectStoreException if an error occurs in the underlying ObjectStore
     * @throws IndexOutOfBoundsException if the batch is off the end of the results
     * @return the rows in the range
     */
    protected List getRowsFromBatch(int batchNo, int start, int end) throws ObjectStoreException {
        List batchList = getBatch(batchNo);

        int startRowInBatch = batchNo * batchSize;
        int endRowInBatch = startRowInBatch + batchSize - 1;

        start = Math.max(start, startRowInBatch);
        end = Math.min(end, endRowInBatch);

        return batchList.subList(start - startRowInBatch, end - startRowInBatch + 1);
    }

    /**
     * Gets a batch by whatever means - maybe batches, maybe the ObjectStore.
     *
     * @param batchNo the batch number to get (zero-indexed)
     * @return a List which is the batch
     * @throws ObjectStoreException if an error occurs in the underlying ObjectStore
     * @throws IndexOutOfBoundsException if the batch is off the end of the results
     */
    protected List getBatch(int batchNo) throws ObjectStoreException {
        List retval = (List) batches.get(new Integer(batchNo));
        if (retval == null) {
            retval = PrefetchManager.doRequest(this, batchNo);
        }
        return retval;
    }

    /**
     * Gets a batch from the ObjectStore
     *
     * @param batchNo the batch number to get (zero-indexed)
     * @return a List which is the batch
     * @throws ObjectStoreException if an error occurs in the underlying ObjectStore
     */
    protected List fetchBatchFromObjectStore(int batchNo) throws ObjectStoreException {
        int start = batchNo * batchSize;
        int limit = batchSize;
        //int end = start + batchSize - 1;
        initialised = true;
        // We now have 3 possibilities:
        // a) This is a full batch
        // b) This is a partial batch, in which case we have reached the end of the results
        //    and can set size.
        // c) An error has occurred - ie. we have gone beyond the end of the results

        List rows = null;
        try {
            rows = os.execute(query, start, limit, optimise);

            synchronized (this) {
                // Now deal with a partial batch, so we can update the maximum size
                if (rows.size() != batchSize) {
                    int size = start + rows.size();
                    maxSize = (maxSize > size ? size : maxSize);
                }
                // Now deal with non-empty batch, so we can update the minimum size
                if (!rows.isEmpty()) {
                    int size = start + rows.size();
                    minSize = (minSize > size ? minSize : size);
                }
            }
        } catch (ObjectStoreLimitReachedException e) {
            throw e;
        } catch (IndexOutOfBoundsException e) {
            synchronized (this) {
                if (rows == null) {
                    maxSize = (maxSize > start ? start : maxSize);
                }
            }
            throw e;
        }

        return rows;
    }

    /**
     * @see AbstractList#get
     * @param index of the ResultsRow required
     * @return the relevant ResultsRow as an Object
     */
    public Object get(int index) {
        List resultList = null;
        try {
            resultList = range(index, index);
        } catch (ObjectStoreException e) {
            LOG.info("get - " + e);
            throw new RuntimeException("ObjectStore error has occured (in get)", e);
        } catch (FlyMineException e) {
            LOG.info("get - " + e);
            throw new RuntimeException("FlyMineException occurred (in get)", e);
        }
        return resultList.get(0);
    }

    /**
     * @see List#subList
     * @param start the index to start from (inclusive)
     * @param end the index to end at (exclusive)
     * @return the sub-list
     */
    public List subList(int start, int end) {
        List ret = null;
        try {
            ret = range(start, end - 1);
        } catch (ObjectStoreException e) {
            LOG.info("subList - " + e);
            throw new RuntimeException("ObjectStore error has occured (in subList)", e);
        } catch (FlyMineException e) {
            LOG.info("subList - " + e);
            throw new RuntimeException("FlyMineException occurred (in subList)", e);
        }
        return ret;
    }

    /**
     * Gets the number of results rows in this Results object
     *
     * @see AbstractList#size
     * @return the number of rows in this Results object
     */
    public int size() {
        //LOG.debug("size - starting                                       Result "
        //        + query.hashCode() + "         size " + minSize + " - " + maxSize);
        if ((minSize == 0) && (maxSize == Integer.MAX_VALUE)) {
            // Fetch the first batch, as it is reasonably likely that it will cover it.
            try {
                get(0);
            } catch (IndexOutOfBoundsException e) {
                // Ignore - that means there are NO rows in this results object.
            }
            return size();
        } else if (minSize * 2 + batchSize < maxSize) {
            // Do a count, because it will probably be a little faster.
            try {
                maxSize = os.count(query);
            } catch (ObjectStoreException e) {
                throw new RuntimeException("ObjectStore error has occured (in size)", e);
            }
            minSize = maxSize;
            LOG.info("size - returning                                      Result "
                    + query.hashCode() + "         size " + maxSize);
        } else {
            int iterations = 0;
            while (minSize < maxSize) {
                try {
                    int toGt = (maxSize == originalMaxSize ? minSize * 2
                            : (minSize + maxSize) / 2);
                    LOG.info("size - getting " + toGt + "                                   Result "
                            + query.hashCode() + "         size " + minSize + " - " + maxSize);
                    get(toGt);
                } catch (ObjectStoreLimitReachedException e) {
                    throw e;
                } catch (IndexOutOfBoundsException e) {
                    // Ignore - this will happen if the end of a batch lies on the
                    // end of the results
                    //LOG.debug("size - Exception caught                               Result "
                    //        + query.hashCode() + "         size " + minSize + " - " + maxSize
                    //        + " " + e);
                }
                iterations++;
            }
            LOG.info("size - returning after " + (iterations > 9 ? "" : " ") + iterations
                    + " iterations                  Result "
                    + query.hashCode() + "         size " + maxSize);
        }
        return maxSize;
    }

    /**
     * Gets the best current estimate of the characteristics of the query.
     *
     * @throws ObjectStoreException if an error occurs in the underlying ObjectStore
     * @return a ResultsInfo object
     */
    public ResultsInfo getInfo() throws ObjectStoreException {
        if ((info == null) || ((lastGet % batchSize) != lastGetAtGetInfoBatch)) {
            info = os.estimate(query);
            lastGetAtGetInfoBatch = lastGet % batchSize;
        }
        return new ResultsInfo(info, minSize, maxSize);
    }

    /**
     * Sets the number of rows requested from the ObjectStore whenever an execute call is made
     *
     * @param size the number of rows
     */
    public void setBatchSize(int size) {
        if (initialised) {
            throw new IllegalStateException("Cannot set batchSize if rows have been retrieved");
        }
        batchSize = size;
    }

    /**
     * Gets the number of rows requested from the ObjectStore whenever an execute call is made
     *
     * @return the number of rows
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Gets the batch for a particular row
     *
     * @param row the row to get the batch for
     * @return the batch number
     */
    protected int getBatchNoForRow(int row) {
        return (int) (row / batchSize);
    }

    /**
     * @see AbstractList#iterator
     */
    public Iterator iterator() {
        return new Iter();
    }

    private class Iter implements Iterator
    {
        /**
         * Index of element to be returned by subsequent call to next.
         */
        int cursor = 0;

        Object nextObject = null;

        public boolean hasNext() {
            //LOG.debug("iterator.hasNext                                      Result "
            //        + query.hashCode() + "         access " + cursor);
            if (cursor < minSize) {
                return true;
            }
            if (nextObject != null) {
                return true;
            }
            try {
                nextObject = get(cursor);
                return true;
            } catch (ObjectStoreLimitReachedException e) {
                throw e;
            } catch (IndexOutOfBoundsException e) {
                // Ignore - it means that we should return false;
            }
            return false;
        }

        public Object next() {
            //LOG.debug("iterator.next                                         Result "
            //        + query.hashCode() + "         access " + cursor);
            Object retval = null;
            if (nextObject != null) {
                retval = nextObject;
                nextObject = null;
            } else {
                try {
                    retval = get(cursor);
                } catch (IndexOutOfBoundsException e) {
                    throw (new NoSuchElementException());
                }
            }
            cursor++;
            return retval;
        }

        public void remove() {
            throw (new UnsupportedOperationException());
        }
    }
}
