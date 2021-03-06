/* Copyright (c) 2001-2011, The HSQL Development Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of the HSQL Development Group nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL HSQL DEVELOPMENT GROUP, HSQLDB.ORG,
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package org.hsqldb.persist;

import org.hsqldb.lib.ArrayUtil;
import org.hsqldb.lib.DoubleIntIndex;

/**
 * Manages allocation of space for rows.<p>
 * Maintains a list of free file blocks with fixed capacity.<p>
 *
 * @author Fred Toussi (fredt@users dot sourceforge.net)
 * @version 2.3.0
 * @since 2.3.0
 */
public class TableSpaceManagerBlocks implements TableSpaceManager {

    DataSpaceManager  spaceManager;
    private final int scale;
    int               mainBlockSize;
    int               spaceID;

    //
    private DoubleIntIndex lookup;
    private final int      capacity;
    private long           releaseCount;
    private long           requestCount;
    private long           requestSize;

    // reporting vars
    long    freeBlockSize;
    boolean isModified;

    //
    long freshBlockPos     = 0;
    long freshBlockFreePos = 0;
    long freshBlockLimit   = 0;

    /**
     *
     */
    public TableSpaceManagerBlocks(DataSpaceManager spaceManager, int tableId,
                                   int fileBlockSize, int capacity,
                                   int fileScale) {

        this.spaceManager  = spaceManager;
        this.scale         = fileScale;
        this.spaceID       = tableId;
        this.mainBlockSize = fileBlockSize;
        lookup             = new DoubleIntIndex(capacity, true);

        lookup.setValuesSearchTarget();

        this.capacity = capacity;
    }

    public boolean hasFileRoom(int blockSize) {
        return freshBlockLimit - freshBlockFreePos > blockSize;
    }

    public void addFileBlock(long blockPos, long blockFreePos,
                             long blockLimit) {

        int released = (int) (freshBlockLimit - freshBlockFreePos);

        if (released > 0) {
            release(freshBlockFreePos / scale, released);
        }

        initialiseFileBlock(null, blockPos, blockFreePos, blockLimit);
    }

    public void initialiseFileBlock(DoubleIntIndex spaceList, long blockPos,
                                    long blockFreePos, long blockLimit) {

        this.freshBlockPos     = blockPos;
        this.freshBlockFreePos = blockFreePos;
        this.freshBlockLimit   = blockLimit;
        this.freeBlockSize     = 0;

        if (spaceList != null) {
            spaceList.copyTo(lookup);

            freeBlockSize = spaceList.getTotalValues();
        }
    }

    boolean getNewMainBlock(int rowSize) {

        int  blockCount = (mainBlockSize + rowSize) / mainBlockSize;
        int  blockSize  = blockCount * mainBlockSize;
        long position   = spaceManager.getFileBlocks(spaceID, blockCount);

        if (position < 0) {
            return false;
        }

        if (position == freshBlockLimit) {
            freshBlockLimit += blockSize;

            return true;
        }

        long released = freshBlockLimit - freshBlockFreePos;

        if (released > 0) {
            release(freshBlockFreePos / scale, (int) released);
        }

        freshBlockPos     = position;
        freshBlockFreePos = position;
        freshBlockLimit   = position + blockSize;

        return true;
    }

    long getNewBlock(int rowSize, boolean asBlocks) {

        if (asBlocks) {
            rowSize = (int) ArrayUtil.getBinaryMultipleCeiling(rowSize,
                    DataSpaceManager.fixedBlockSizeUnit);
        }

        if (freshBlockFreePos + rowSize > freshBlockLimit) {
            boolean result = getNewMainBlock(rowSize);

            if (!result) {
                return -1;
            }
        }

        long position = freshBlockFreePos;

        if (asBlocks) {
            position = ArrayUtil.getBinaryMultipleCeiling(position,
                    DataSpaceManager.fixedBlockSizeUnit);

            long released = position - freshBlockFreePos;

            if (released > 0) {
                release(freshBlockFreePos / scale, (int) released);

                freshBlockFreePos = position;
            }
        }

        freshBlockFreePos += rowSize;

        return position / scale;
    }

    public int getSpaceID() {
        return spaceID;
    }

    synchronized public void release(long pos, int rowSize) {

        isModified = true;

        releaseCount++;

        if (lookup.size() == capacity) {
            resetList();
        }

        if (pos < Integer.MAX_VALUE) {
            lookup.add(pos, rowSize);

            freeBlockSize += rowSize;
        }
    }

    /**
     * Returns the position of a free block or 0.
     */
    synchronized public long getFilePosition(int rowSize, boolean asBlocks) {

        if (capacity == 0) {
            return getNewBlock(rowSize, asBlocks);
        }

        if (asBlocks) {
            rowSize = (int) ArrayUtil.getBinaryMultipleCeiling(rowSize,
                    DataSpaceManager.fixedBlockSizeUnit);
        }

        int index = -1;

        if (lookup.size() > 0) {
            if (lookup.getValue(0) >= rowSize) {
                index = 0;
            } else {
                index = lookup.findFirstGreaterEqualKeyIndex(rowSize);
            }
        }

        if (index == -1) {
            return getNewBlock(rowSize, asBlocks);
        }

        if (asBlocks) {
            for (; index < lookup.size(); index++) {
                long pos = lookup.getKey(index);

                if (pos % (DataSpaceManager.fixedBlockSizeUnit / scale) == 0) {
                    break;
                }
            }

            if (index == lookup.size()) {
                return getNewBlock(rowSize, asBlocks);
            }
        }

        // statistics for successful requests only - to be used later for midSize
        requestCount++;

        requestSize += rowSize;

        int length     = lookup.getValue(index);
        int difference = length - rowSize;
        int key        = lookup.getKey(index);

        lookup.remove(index);

        if (difference > 0) {
            long pos = key + (rowSize / scale);

            lookup.add(pos, difference);
        }

        freeBlockSize -= rowSize;

        return key;
    }

    public void reset() {

        compactLookup();
        spaceManager.freeTableSpace(spaceID, lookup, freshBlockFreePos,
                                    freshBlockLimit);
        lookup.removeAll();

        freshBlockPos     = 0;
        freshBlockFreePos = 0;
        freshBlockLimit   = 0;
        freeBlockSize     = 0;
    }

    public long getLostBlocksSize() {
        return freeBlockSize;
    }

    public void setSpaceManager(DataSpaceManager spaceManager, int spaceID) {
        this.spaceManager = spaceManager;
        this.spaceID      = spaceID;
    }

    private void resetList() {

        if (compactLookup()) {
            return;
        }

        // dummy args for file block release
        spaceManager.freeTableSpace(spaceID, lookup, freshBlockFreePos,
                                    freshBlockFreePos);
        lookup.removeAll();

        freeBlockSize = 0;
    }

    private boolean compactLookup() {

        lookup.setKeysSearchTarget();
        lookup.sort();

        int[] keys   = lookup.getKeys();
        int[] values = lookup.getValues();
        int   base   = 0;

        for (int i = 1; i < lookup.size(); i++) {
            int key   = keys[base];
            int value = values[base];

            if (key + value / scale == keys[i]) {
                values[base] += values[i];    // base updated
            } else {
                base++;

                if (base != i) {
                    keys[base]   = keys[i];
                    values[base] = values[i];
                }
            }
        }

        for (int i = base + 1; i < lookup.size(); i++) {
            keys[i]   = 0;
            values[i] = 0;
        }

        if (lookup.size() != base + 1) {
            lookup.setSize(base + 1);
            lookup.setValuesSearchTarget();
            lookup.sort();

            return true;
        }

        lookup.setValuesSearchTarget();
        lookup.sort();

        return false;
    }
}
