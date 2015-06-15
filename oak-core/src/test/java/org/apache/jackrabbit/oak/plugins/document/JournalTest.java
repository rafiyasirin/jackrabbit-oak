/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.plugins.document;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.plugins.document.memory.MemoryDocumentStore;
import org.apache.jackrabbit.oak.plugins.document.util.Utils;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.blob.MemoryBlobStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.commit.Observer;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStateDiff;
import org.apache.jackrabbit.oak.stats.Clock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.mongodb.DB;

public class JournalTest {

    private static final boolean MONGO_DB = false;
//    private static final boolean MONGO_DB = true;
    
    private TestBuilder builder;

    private MemoryDocumentStore ds;
    private MemoryBlobStore bs;

    private List<DocumentMK> mks = Lists.newArrayList();

    class DiffingObserver implements Observer, Runnable, NodeStateDiff {

        final List<DocumentNodeState> incomingRootStates1 = Lists.newArrayList();
        final List<DocumentNodeState> diffedRootStates1 = Lists.newArrayList();
        
        DocumentNodeState oldRoot = null;
        
        DiffingObserver(boolean startInBackground) {
            if (startInBackground) {
                // start the diffing in the background - so as to not
                // interfere with the contentChanged call
                Thread th = new Thread(this);
                th.setDaemon(true);
                th.start();
            }
        }

        public void clear() {
            synchronized(incomingRootStates1) {
                incomingRootStates1.clear();
                diffedRootStates1.clear();
            }
        }
        
        @Override
        public void contentChanged(NodeState root, CommitInfo info) {
            synchronized(incomingRootStates1) {
                incomingRootStates1.add((DocumentNodeState) root);
                incomingRootStates1.notifyAll();
            }
        }
        
        public void processAll() {
            while(processOne()) {
                // continue
            }
        }

        public boolean processOne() {
            DocumentNodeState newRoot;
            synchronized(incomingRootStates1) {
                if (incomingRootStates1.size()==0) {
                    return false;
                }
                newRoot = incomingRootStates1.remove(0);
            }
            if (oldRoot!=null) {
                newRoot.compareAgainstBaseState(oldRoot, this);
            }
            oldRoot = newRoot;
            synchronized(incomingRootStates1) {
                diffedRootStates1.add(newRoot);
            }
            return true;
        }
        
        @Override
        public void run() {
            while(true) {
                DocumentNodeState newRoot;
                synchronized(incomingRootStates1) {
                    while(incomingRootStates1.size()==0) {
                        try {
                            incomingRootStates1.wait();
                        } catch (InterruptedException e) {
                            // ignore
                            continue;
                        }
                    }
                    newRoot = incomingRootStates1.remove(0);
                }
                if (oldRoot!=null) {
                    newRoot.compareAgainstBaseState(oldRoot, this);
                }
                oldRoot = newRoot;
                synchronized(incomingRootStates1) {
                    diffedRootStates1.add(newRoot);
                }
            }
        }

        @Override
        public boolean propertyAdded(PropertyState after) {
            return true;
        }

        @Override
        public boolean propertyChanged(PropertyState before, PropertyState after) {
            return true;
        }

        @Override
        public boolean propertyDeleted(PropertyState before) {
            return true;
        }

        @Override
        public boolean childNodeAdded(String name, NodeState after) {
            return true;
        }

        @Override
        public boolean childNodeChanged(String name, NodeState before,
                NodeState after) {
            return true;
        }

        @Override
        public boolean childNodeDeleted(String name, NodeState before) {
            return true;
        }

        public int getTotal() {
            synchronized(incomingRootStates1) {
                return incomingRootStates1.size() + diffedRootStates1.size();
            }
        }
        
    }
    
    @Test
    public void largeCleanupTest() throws Exception {
        // create more than DELETE_BATCH_SIZE of entries and clean them up
        // should make sure to loop in JournalGarbageCollector.gc such
        // that it would find issue described here:
        // https://issues.apache.org/jira/browse/OAK-2829?focusedCommentId=14585733&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-14585733
        
        doLargeCleanupTest(0, 100);
        doLargeCleanupTest(200, 1000);// using offset as to not make sure to always create new entries
        doLargeCleanupTest(2000, 10000);
        doLargeCleanupTest(20000, 30000); // using 'size' much larger than 30k will be tremendously slow due to ordered node
    }
    
    private void doLargeCleanupTest(int offset, int size) throws Exception {
        Clock clock = new Clock.Virtual();
        DocumentMK mk1 = createMK(0 /* clusterId: 0 => uses clusterNodes collection */, 0);
        DocumentNodeStore ns1 = mk1.getNodeStore();
        // make sure we're visible and marked as active
        ns1.renewClusterIdLease();
        JournalGarbageCollector gc = new JournalGarbageCollector(ns1);
        clock.getTimeIncreasing();
        clock.getTimeIncreasing();
        gc.gc(0, TimeUnit.MILLISECONDS); // cleanup everything that might still be there 
        
        // create entries as parametrized:
        for(int i=offset; i<size+offset; i++) {
            mk1.commit("/", "+\"regular"+i+"\": {}", null, null);
            // always run background ops to 'flush' the change
            // into the journal:
            ns1.runBackgroundOperations();
        }
        Thread.sleep(100); // sleep 100millis
        assertEquals(size, gc.gc(0, TimeUnit.MILLISECONDS)); // should now be able to clean up everything
        
    }
    
    @Test
    public void cleanupTest() throws Exception {
        DocumentMK mk1 = createMK(0 /* clusterId: 0 => uses clusterNodes collection */, 0);
        DocumentNodeStore ns1 = mk1.getNodeStore();
        // make sure we're visible and marked as active
        ns1.renewClusterIdLease();
        JournalGarbageCollector gc = new JournalGarbageCollector(ns1);
        // first clean up
        Thread.sleep(100); // OAK-2979 : wait 100ms before doing the cleanup
        gc.gc(1, TimeUnit.MILLISECONDS);
        Thread.sleep(100); // sleep just quickly
        assertEquals(0, gc.gc(1, TimeUnit.DAYS));
        assertEquals(0, gc.gc(6, TimeUnit.HOURS));
        assertEquals(0, gc.gc(1, TimeUnit.HOURS));
        assertEquals(0, gc.gc(10, TimeUnit.MINUTES));
        assertEquals(0, gc.gc(1, TimeUnit.MINUTES));
        assertEquals(0, gc.gc(1, TimeUnit.SECONDS));
        assertEquals(0, gc.gc(1, TimeUnit.MILLISECONDS));
        
        // create some entries that can be deleted thereupon
        mk1.commit("/", "+\"regular1\": {}", null, null);
        mk1.commit("/", "+\"regular2\": {}", null, null);
        mk1.commit("/", "+\"regular3\": {}", null, null);
        mk1.commit("/regular2", "+\"regular4\": {}", null, null);
        Thread.sleep(100); // sleep 100millis
        assertEquals(0, gc.gc(5, TimeUnit.SECONDS));
        assertEquals(0, gc.gc(1, TimeUnit.MILLISECONDS));
        ns1.runBackgroundOperations();
        mk1.commit("/", "+\"regular5\": {}", null, null);
        ns1.runBackgroundOperations();
        mk1.commit("/", "+\"regular6\": {}", null, null);
        ns1.runBackgroundOperations();
        Thread.sleep(100); // sleep 100millis
        assertEquals(0, gc.gc(5, TimeUnit.SECONDS));
        assertEquals(3, gc.gc(1, TimeUnit.MILLISECONDS));
    }
    
    @Test
    public void journalTest() throws Exception {
        DocumentMK mk1 = createMK(1, 0);
        DocumentNodeStore ns1 = mk1.getNodeStore();
        CountingDocumentStore countingDocStore1 = builder.actualStore;
        CountingTieredDiffCache countingDiffCache1 = builder.actualDiffCache;

        DocumentMK mk2 = createMK(2, 0);
        DocumentNodeStore ns2 = mk2.getNodeStore();
        CountingDocumentStore countingDocStore2 = builder.actualStore;
        CountingTieredDiffCache countingDiffCache2 = builder.actualDiffCache;

        final DiffingObserver observer = new DiffingObserver(false);
        ns1.addObserver(observer);
        
        ns1.runBackgroundOperations();
        ns2.runBackgroundOperations();
        observer.processAll(); // to make sure we have an 'oldRoot'
        observer.clear();
        countingDocStore1.resetCounters();
        countingDocStore2.resetCounters();
        countingDocStore1.printStacks = true;
        countingDiffCache1.resetLoadCounter();
        countingDiffCache2.resetLoadCounter();

        mk2.commit("/", "+\"regular1\": {}", null, null);
        mk2.commit("/", "+\"regular2\": {}", null, null);
        mk2.commit("/", "+\"regular3\": {}", null, null);
        mk2.commit("/regular2", "+\"regular4\": {}", null, null);
        // flush to journal
        ns2.runBackgroundOperations();
        
        // nothing notified yet
        assertEquals(0, observer.getTotal());
        assertEquals(0, countingDocStore1.getNumFindCalls(Collection.NODES));
        assertEquals(0, countingDocStore1.getNumQueryCalls(Collection.NODES));
        assertEquals(0, countingDocStore1.getNumRemoveCalls(Collection.NODES));
        assertEquals(0, countingDocStore1.getNumCreateOrUpdateCalls(Collection.NODES));
        assertEquals(0, countingDiffCache1.getLoadCount());
        
        // let node 1 read those changes
        System.err.println("run background ops");
        ns1.runBackgroundOperations();
        mk2.commit("/", "+\"regular5\": {}", null, null);
        ns2.runBackgroundOperations();
        ns1.runBackgroundOperations();
        // and let the observer process everything
        observer.processAll();
        countingDocStore1.printStacks = false;
        
        // now expect 1 entry in rootStates
        assertEquals(2, observer.getTotal());
        assertEquals(0, countingDiffCache1.getLoadCount());
        assertEquals(0, countingDocStore1.getNumRemoveCalls(Collection.NODES));
        assertEquals(0, countingDocStore1.getNumCreateOrUpdateCalls(Collection.NODES));
        assertEquals(0, countingDocStore1.getNumQueryCalls(Collection.NODES));
//        assertEquals(0, countingDocStore1.getNumFindCalls(Collection.NODES));
    }
    
    @Test
    public void externalBranchChange() throws Exception {
        DocumentMK mk1 = createMK(1, 0);
        DocumentNodeStore ns1 = mk1.getNodeStore();
        DocumentMK mk2 = createMK(2, 0);
        DocumentNodeStore ns2 = mk2.getNodeStore();
        
        ns1.runBackgroundOperations();
        ns2.runBackgroundOperations();

        mk1.commit("/", "+\"regular1\": {}", null, null);
        // flush to journal
        ns1.runBackgroundOperations();
        mk1.commit("/regular1", "+\"regular1child\": {}", null, null);
        // flush to journal
        ns1.runBackgroundOperations();
        mk1.commit("/", "+\"regular2\": {}", null, null);
        // flush to journal
        ns1.runBackgroundOperations();
        mk1.commit("/", "+\"regular3\": {}", null, null);
        // flush to journal
        ns1.runBackgroundOperations();
        mk1.commit("/", "+\"regular4\": {}", null, null);
        // flush to journal
        ns1.runBackgroundOperations();
        mk1.commit("/", "+\"regular5\": {}", null, null);
        // flush to journal
        ns1.runBackgroundOperations();
        String b1 = mk1.branch(null);
        b1 = mk1.commit("/", "+\"branchVisible\": {}", b1, null);
        mk1.merge(b1, null);
        
        // to flush the branch commit either dispose of mk1
        // or run the background operations explicitly 
        // (as that will propagate the lastRev to the root)
        ns1.runBackgroundOperations();
        ns2.runBackgroundOperations();
        
        String nodes = mk2.getNodes("/", null, 0, 0, 100, null);
        assertEquals("{\"branchVisible\":{},\"regular1\":{},\"regular2\":{},\"regular3\":{},\"regular4\":{},\"regular5\":{},\":childNodeCount\":6}", nodes);
    }
    
    /** Inspired by LastRevRecoveryTest.testRecover() - simplified and extended with journal related asserts **/
    @Test
    public void lastRevRecoveryJournalTest() throws Exception {
        doLastRevRecoveryJournalTest(false);
    }
    
    /** Inspired by LastRevRecoveryTest.testRecover() - simplified and extended with journal related asserts **/
    @Test
    public void lastRevRecoveryJournalTestWithConcurrency() throws Exception {
        doLastRevRecoveryJournalTest(true);
    }
    
    void doLastRevRecoveryJournalTest(boolean testConcurrency) throws Exception {
        DocumentMK mk1 = createMK(0 /*clusterId via clusterNodes collection*/, 0);
        DocumentNodeStore ds1 = mk1.getNodeStore();
        int c1Id = ds1.getClusterId();
        DocumentMK mk2 = createMK(0 /*clusterId via clusterNodes collection*/, 0);
        DocumentNodeStore ds2 = mk2.getNodeStore();
        final int c2Id = ds2.getClusterId();
        
        // should have 1 each with just the root changed
        assertJournalEntries(ds1, "{}");
        assertJournalEntries(ds2, "{}");
        assertEquals(1, countJournalEntries(ds1, 10)); 
        assertEquals(1, countJournalEntries(ds2, 10));
        
        //1. Create base structure /x/y
        NodeBuilder b1 = ds1.getRoot().builder();
        b1.child("x").child("y");
        ds1.merge(b1, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        ds1.runBackgroundOperations();

        //lastRev are persisted directly for new nodes. In case of
        // updates they are persisted via background jobs

        //1.2 Get last rev populated for root node for ds2
        ds2.runBackgroundOperations();
        NodeBuilder b2 = ds2.getRoot().builder();
        b2.child("x").setProperty("f1","b1");
        ds2.merge(b2, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        ds2.runBackgroundOperations();

        //2. Add a new node /x/y/z
        b2 = ds2.getRoot().builder();
        b2.child("x").child("y").child("z").setProperty("foo", "bar");
        ds2.merge(b2, EmptyHook.INSTANCE, CommitInfo.EMPTY);

        //Refresh DS1
        ds1.runBackgroundOperations();

        final NodeDocument z1 = getDocument(ds1, "/x/y/z");
        NodeDocument y1 = getDocument(ds1, "/x/y");
        final NodeDocument x1 = getDocument(ds1, "/x");

        Revision zlastRev2 = z1.getLastRev().get(c2Id);
        // /x/y/z is a new node and does not have a _lastRev
        assertNull(zlastRev2);
        Revision head2 = ds2.getHeadRevision();

        //lastRev should not be updated for C #2
        assertNull(y1.getLastRev().get(c2Id));

        final LastRevRecoveryAgent recovery = new LastRevRecoveryAgent(ds1);

        // besides the former root change, now 1 also has 
        final String change1 = "{\"x\":{\"y\":{}}}";
        assertJournalEntries(ds1, "{}", change1);
        final String change2 = "{\"x\":{}}";
        assertJournalEntries(ds2, "{}", change2);


        String change2b = "{\"x\":{\"y\":{\"z\":{}}}}";

        if (!testConcurrency) {
            //Do not pass y1 but still y1 should be updated
            recovery.recover(Iterators.forArray(x1,z1), c2Id);
    
            //Post recovery the lastRev should be updated for /x/y and /x
            assertEquals(head2, getDocument(ds1, "/x/y").getLastRev().get(c2Id));
            assertEquals(head2, getDocument(ds1, "/x").getLastRev().get(c2Id));
            assertEquals(head2, getDocument(ds1, "/").getLastRev().get(c2Id));
    
            // now 1 is unchanged, but 2 was recovered now, so has one more:
            assertJournalEntries(ds1, "{}", change1); // unchanged
            assertJournalEntries(ds2, "{}", change2, change2b);
            
            // just some no-ops:
            recovery.recover(c2Id);
            List<NodeDocument> emptyList = new LinkedList<NodeDocument>();
            recovery.recover(emptyList.iterator(), c2Id);
            assertJournalEntries(ds1, "{}", change1); // unchanged
            assertJournalEntries(ds2, "{}", change2, change2b);

        } else {
        
            // do some concurrency testing as well to check if 
            final int NUM_THREADS = 200;
            final CountDownLatch ready = new CountDownLatch(NUM_THREADS);
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch end = new CountDownLatch(NUM_THREADS);
            for(int i=0; i<NUM_THREADS; i++) {
                final List<Throwable> throwables = new LinkedList<Throwable>();
                Thread th = new Thread(new Runnable() {
    
                    @Override
                    public void run() {
                        try {
                            ready.countDown();
                            start.await();
                            recovery.recover(Iterators.forArray(x1,z1), c2Id);
                        } catch (Throwable e) {
                            synchronized(throwables) {
                                throwables.add(e);
                            }
                        } finally {
                            end.countDown();
                        }
                    }
                    
                });
                th.start();
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertTrue(end.await(20, TimeUnit.SECONDS));
            assertJournalEntries(ds1, "{}", change1); // unchanged
            assertJournalEntries(ds2, "{}", change2, change2b);
        }
    }

    void assertJournalEntries(DocumentNodeStore ds, String... expectedChanges) {
        List<String> exp = new LinkedList<String>(Arrays.asList(expectedChanges));
        for(boolean branch : new Boolean[]{false, true}) {
            String fromKey = JournalEntry.asId(new Revision(0, 0, ds.getClusterId(), branch));
            String toKey = JournalEntry.asId(new Revision(System.currentTimeMillis()+1000, 0, ds.getClusterId(), branch));
            List<JournalEntry> entries = ds.getDocumentStore().query(Collection.JOURNAL, fromKey, toKey, expectedChanges.length+5);
            if (entries.size()>0) {
                for (Iterator<JournalEntry> it = entries.iterator(); it.hasNext();) {
                    JournalEntry journalEntry = it.next();
                    if (!exp.remove(journalEntry.get("_c"))) {
                        fail("Found an unexpected change: "+journalEntry.get("_c")+", while all I expected was: "+expectedChanges);
                    }
                }
            }
        }
        if (exp.size()>0) {
            fail("Did not find all expected changes, left over: "+exp+" (from original list which is: "+expectedChanges+")");
        }
    }

    int countJournalEntries(DocumentNodeStore ds, int max) {
        int total = 0;
        for(boolean branch : new Boolean[]{false, true}) {
            String fromKey = JournalEntry.asId(new Revision(0, 0, ds.getClusterId(), branch));
            String toKey = JournalEntry.asId(new Revision(System.currentTimeMillis()+1000, 0, ds.getClusterId(), branch));
            List<JournalEntry> entries = ds.getDocumentStore().query(Collection.JOURNAL, fromKey, toKey, max);
            total+=entries.size();
        }
        return total;
    }
    
    private NodeDocument getDocument(DocumentNodeStore nodeStore, String path) {
        return nodeStore.getDocumentStore().find(Collection.NODES, Utils.getIdFromPath(path));
    }

    @Before
    @After
    public void clear() {
        for (DocumentMK mk : mks) {
            mk.dispose();
        }
        mks.clear();
        if (MONGO_DB) {
            DB db = MongoUtils.getConnection().getDB();
            MongoUtils.dropCollections(db);
        }
    }

    private final class TestBuilder extends DocumentMK.Builder {
        private CountingDocumentStore actualStore;
        private CountingTieredDiffCache actualDiffCache;

        @Override
        public DocumentStore getDocumentStore() {
            if (actualStore==null) {
                actualStore = new CountingDocumentStore(super.getDocumentStore());
            }
            return actualStore;
        }
        
        @Override
        public DiffCache getDiffCache() {
            if (actualDiffCache==null) {
                actualDiffCache = new CountingTieredDiffCache(this);
            }
            return actualDiffCache;
        }
    }

    private DocumentMK createMK(int clusterId, int asyncDelay) {
        if (MONGO_DB) {
            DB db = MongoUtils.getConnection(/*"oak-observation"*/).getDB();
            builder = newDocumentMKBuilder();
            return register(builder.setMongoDB(db)
                    .setClusterId(clusterId).setAsyncDelay(asyncDelay).open());
        } else {
            if (ds == null) {
                ds = new MemoryDocumentStore();
            }
            if (bs == null) {
                bs = new MemoryBlobStore();
            }
            return createMK(clusterId, asyncDelay, ds, bs);
        }
    }
    
    private TestBuilder newDocumentMKBuilder() {
        return new TestBuilder();
    }

    private DocumentMK createMK(int clusterId, int asyncDelay,
                             DocumentStore ds, BlobStore bs) {
        builder = newDocumentMKBuilder();
        return register(builder.setDocumentStore(ds)
                .setBlobStore(bs).setClusterId(clusterId)
                .setAsyncDelay(asyncDelay).open());
    }

    private DocumentMK register(DocumentMK mk) {
        mks.add(mk);
        return mk;
    }

    private void disposeMK(DocumentMK mk) {
        mk.dispose();
        for (int i = 0; i < mks.size(); i++) {
            if (mks.get(i) == mk) {
                mks.remove(i);
            }
        }
    }
}
