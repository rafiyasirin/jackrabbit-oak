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
package org.apache.jackrabbit.oak.plugins.document.blob.ds;

import java.util.Date;

import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreUtils;
import org.apache.jackrabbit.oak.plugins.document.DocumentMK;
import org.apache.jackrabbit.oak.plugins.document.MongoBlobGCTest;
import org.apache.jackrabbit.oak.plugins.document.MongoUtils;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;

/**
 * Test for MongoMK GC with {@link DataStoreBlobStore}
 * 
 */
public class MongoDataStoreBlobGCTest extends MongoBlobGCTest {
    protected Date startDate;
    protected DataStoreBlobStore blobStore;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        try {
            Assume.assumeNotNull(DataStoreUtils.getBlobStore());
        } catch (Exception e) {
            Assume.assumeNoException(e);
        }
    }

    @Override
    protected DocumentMK.Builder addToBuilder(DocumentMK.Builder mk) {
        return super.addToBuilder(mk).setBlobStore(blobStore);
    }

    @Before
    @Override
    public void setUpConnection() throws Exception {
        startDate = new Date();
        blobStore = DataStoreUtils.getBlobStore(folder.newFolder());
        super.setUpConnection();
    }
}
