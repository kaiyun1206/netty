/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer;

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;

import java.nio.ByteBuffer;

/**
 * Simplistic {@link ByteBufAllocator} implementation that does not pool anything.
 */
public final class UnpooledByteBufAllocator extends AbstractByteBufAllocator implements ByteBufAllocatorMetric {

    private final LongCounter directCounter = PlatformDependent.newLongCounter();
    private final LongCounter heapCounter = PlatformDependent.newLongCounter();
    private final boolean disableLeakDetector;

    /**
     * Default instance which uses leak-detection for direct buffers.
     */
    public static final UnpooledByteBufAllocator DEFAULT =
            new UnpooledByteBufAllocator(PlatformDependent.directBufferPreferred());

    /**
     * Create a new instance which uses leak-detection for direct buffers.
     *
     * @param preferDirect {@code true} if {@link #buffer(int)} should try to allocate a direct buffer rather than
     *                     a heap buffer
     */
    public UnpooledByteBufAllocator(boolean preferDirect) {
        this(preferDirect, false);
    }

    /**
     * Create a new instance
     *
     * @param preferDirect {@code true} if {@link #buffer(int)} should try to allocate a direct buffer rather than
     *                     a heap buffer
     * @param disableLeakDetector {@code true} if the leak-detection should be disabled completely for this
     *                            allocator. This can be useful if the user just want to depend on the GC to handle
     *                            direct buffers when not explicit released.
     */
    public UnpooledByteBufAllocator(boolean preferDirect, boolean disableLeakDetector) {
        super(preferDirect);
        this.disableLeakDetector = disableLeakDetector;
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        return PlatformDependent.hasUnsafe() ?
                new TrackedUnpooledUnsafeHeapByteBuf(this, initialCapacity, maxCapacity) :
                new TrackedUnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        final ByteBuf buf;
        if (PlatformDependent.hasUnsafe()) {
            buf = PlatformDependent.useDirectBufferNoCleaner() ?
                    new TrackedUnpooledUnsafeNoCleanerDirectByteBuf(this, initialCapacity, maxCapacity) :
                    new TrackedUnpooledUnsafeDirectByteBuf(this, initialCapacity, maxCapacity);
        } else {
            buf = new TrackedUnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }
        return disableLeakDetector ? buf : toLeakAwareBuffer(buf);
    }

    @Override
    public CompositeByteBuf compositeHeapBuffer(int maxNumComponents) {
        CompositeByteBuf buf = new CompositeByteBuf(this, false, maxNumComponents);
        return disableLeakDetector ? buf : toLeakAwareBuffer(buf);
    }

    @Override
    public CompositeByteBuf compositeDirectBuffer(int maxNumComponents) {
        CompositeByteBuf buf = new CompositeByteBuf(this, true, maxNumComponents);
        return disableLeakDetector ? buf : toLeakAwareBuffer(buf);
    }

    @Override
    public boolean isDirectBufferPooled() {
        return false;
    }

    @Override
    public long usedHeapMemory() {
        return heapCounter.value();
    }

    @Override
    public long usedDirectMemory() {
        return directCounter.value();
    }

    private static final class TrackedUnpooledUnsafeHeapByteBuf extends UnpooledUnsafeHeapByteBuf {
        TrackedUnpooledUnsafeHeapByteBuf(UnpooledByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
            super(alloc, initialCapacity, maxCapacity);
        }

        @Override
        byte[] allocateArray(int initialCapacity) {
            byte[] bytes = super.allocateArray(initialCapacity);
            ((UnpooledByteBufAllocator) alloc()).heapCounter.add(bytes.length);
            return bytes;
        }

        @Override
        void freeArray(byte[] array) {
            int length = array.length;
            super.freeArray(array);
            ((UnpooledByteBufAllocator) alloc()).heapCounter.add(-length);
        }
    }

    private static final class TrackedUnpooledHeapByteBuf extends UnpooledHeapByteBuf {
        TrackedUnpooledHeapByteBuf(UnpooledByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
            super(alloc, initialCapacity, maxCapacity);
        }

        @Override
        byte[] allocateArray(int initialCapacity) {
            byte[] bytes = super.allocateArray(initialCapacity);
            ((UnpooledByteBufAllocator) alloc()).heapCounter.add(bytes.length);
            return bytes;
        }

        @Override
        void freeArray(byte[] array) {
            int length = array.length;
            super.freeArray(array);
            ((UnpooledByteBufAllocator) alloc()).heapCounter.add(-length);
        }
    }

    private static final class TrackedUnpooledUnsafeNoCleanerDirectByteBuf
            extends UnpooledUnsafeNoCleanerDirectByteBuf {
        TrackedUnpooledUnsafeNoCleanerDirectByteBuf(
                UnpooledByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
            super(alloc, initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuffer allocateDirect(int initialCapacity) {
            ByteBuffer buffer = super.allocateDirect(initialCapacity);
            ((UnpooledByteBufAllocator) alloc()).directCounter.add(buffer.capacity());
            return buffer;
        }

        @Override
        ByteBuffer reallocateDirect(ByteBuffer oldBuffer, int initialCapacity) {
            int capacity = oldBuffer.capacity();
            ByteBuffer buffer = super.reallocateDirect(oldBuffer, initialCapacity);
            ((UnpooledByteBufAllocator) alloc()).directCounter.add(buffer.capacity() - capacity);
            return buffer;
        }

        @Override
        protected void freeDirect(ByteBuffer buffer) {
            int capacity = buffer.capacity();
            super.freeDirect(buffer);
            ((UnpooledByteBufAllocator) alloc()).directCounter.add(-capacity);
        }
    }

    private static final class TrackedUnpooledUnsafeDirectByteBuf extends UnpooledUnsafeDirectByteBuf {
        TrackedUnpooledUnsafeDirectByteBuf(
                UnpooledByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
            super(alloc, initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuffer allocateDirect(int initialCapacity) {
            ByteBuffer buffer = super.allocateDirect(initialCapacity);
            ((UnpooledByteBufAllocator) alloc()).directCounter.add(buffer.capacity());
            return buffer;
        }

        @Override
        protected void freeDirect(ByteBuffer buffer) {
            int capacity = buffer.capacity();
            super.freeDirect(buffer);
            ((UnpooledByteBufAllocator) alloc()).directCounter.add(-capacity);
        }
    }

    private static final class TrackedUnpooledDirectByteBuf extends UnpooledDirectByteBuf {
        TrackedUnpooledDirectByteBuf(
                UnpooledByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
            super(alloc, initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuffer allocateDirect(int initialCapacity) {
            ByteBuffer buffer = super.allocateDirect(initialCapacity);
            ((UnpooledByteBufAllocator) alloc()).directCounter.add(buffer.capacity());
            return buffer;
        }

        @Override
        protected void freeDirect(ByteBuffer buffer) {
            int capacity = buffer.capacity();
            super.freeDirect(buffer);
            ((UnpooledByteBufAllocator) alloc()).directCounter.add(-capacity);
        }
    }
}
