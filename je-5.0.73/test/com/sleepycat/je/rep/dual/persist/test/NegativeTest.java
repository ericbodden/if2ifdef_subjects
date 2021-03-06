/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2013 Oracle and/or its affiliates.  All rights reserved.
 *
 */
package com.sleepycat.je.rep.dual.persist.test;

import java.util.List;

import org.junit.runners.Parameterized.Parameters;

public class NegativeTest extends com.sleepycat.persist.test.NegativeTest {

    public NegativeTest(String type) {
        super(type);
    }
    
    @Parameters
    public static List<Object[]> genParams() {
        
        return getTxnTypes(null, true);
    }
}
