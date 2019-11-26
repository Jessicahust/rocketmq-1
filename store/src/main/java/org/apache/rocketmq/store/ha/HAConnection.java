/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.ha;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.apache.rocketmq.store.SelectMappedBufferResult;

public class HAConnection {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private final HAService haService;
    private final SocketChannel socketChannel;
    private final String clientAddr;
    private WriteSocketService writeSocketService;
    private ReadSocketService readSocketService;

    private volatile long slaveRequestOffset = -1;
    private volatile long slaveAckOffset = -1;

    public HAConnection(final HAService haService, final SocketChannel socketChannel) throws IOException {
        this.haService = haService;
        this.socketChannel = socketChannel;
        this.clientAddr = this.socketChannel.socket().getRemoteSocketAddress().toString();
        this.socketChannel.configureBlocking(false);
        this.socketChannel.socket().setSoLinger(false, -1);
        this.socketChannel.socket().setTcpNoDelay(true);
        this.socketChannel.socket().setReceiveBufferSize(1024 * 64);
        this.socketChannel.socket().setSendBufferSize(1024 * 64);
        this.writeSocketService = new WriteSocketService(this.socketChannel);
        this.readSocketService = new ReadSocketService(this.socketChannel);
        this.haService.getConnectionCount().incrementAndGet();
    }

    public void start() {
        //启动{@link #readSocketService}读取Slave传过来的数据
        this.readSocketService.start();
        //启动{@link #writeSocketService}向Channel写入消息,同步给Slave
        this.writeSocketService.start();
    }

    public void shutdown() {
        this.writeSocketService.shutdown(true);
        this.readSocketService.shutdown(true);
        this.close();
    }

    public void close() {
        if (this.socketChannel != null) {
            try {
                this.socketChannel.close();
            } catch (IOException e) {
                HAConnection.log.error("", e);
            }
        }
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    class ReadSocketService extends ServiceThread {
        private static final int READ_MAX_BUFFER_SIZE = 1024 * 1024;
        private final Selector selector;
        private final SocketChannel socketChannel;
        private final ByteBuffer byteBufferRead = ByteBuffer.allocate(READ_MAX_BUFFER_SIZE);
        private int processPostion = 0;
        private volatile long lastReadTimestamp = System.currentTimeMillis();

        public ReadSocketService(final SocketChannel socketChannel) throws IOException {
            this.selector = RemotingUtil.openSelector();
            this.socketChannel = socketChannel;
            this.socketChannel.register(this.selector, SelectionKey.OP_READ);
            this.thread.setDaemon(true);
        }

        @Override
        public void run() {
            HAConnection.log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    //最多阻塞1S钟,当有一个Channel发送了数据后，立即中断阻塞
                    //若1S时间内也没有Channel发送了数据,则processReadEvent()会立即返回true,然后再次阻塞,循环如此
                    this.selector.select(1000);
                    boolean ok = this.processReadEvent();
                    if (!ok) {
                        HAConnection.log.error("processReadEvent error");
                        break;
                    }
                    // Slave超过20S没有返回数据，断开连接
                    long interval = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now() - this.lastReadTimestamp;
                    //超过20S时间没有收到Slave传递的消息,断开Slave连接
                    if (interval > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaHousekeepingInterval()) {
                        log.warn("ha housekeeping, found this connection[" + HAConnection.this.clientAddr + "] expired, " + interval);
                        break;
                    }
                } catch (Exception e) {
                    HAConnection.log.error(this.getServiceName() + " service has exception.", e);
                    break;
                }
            }

            this.makeStop();

            writeSocketService.makeStop();

            haService.removeConnection(HAConnection.this);

            HAConnection.this.haService.getConnectionCount().decrementAndGet();

            SelectionKey sk = this.socketChannel.keyFor(this.selector);
            if (sk != null) {
                sk.cancel();
            }

            try {
                this.selector.close();
                this.socketChannel.close();
            } catch (IOException e) {
                HAConnection.log.error("", e);
            }

            HAConnection.log.info(this.getServiceName() + " service end");
        }

        @Override
        public String getServiceName() {
            return ReadSocketService.class.getSimpleName();
        }

        private boolean processReadEvent() {
            int readSizeZeroTimes = 0;

            // 重置读取缓冲区以及processPosition
            if (!this.byteBufferRead.hasRemaining()) {
                //超过范围，重置
                this.byteBufferRead.flip();
                this.processPostion = 0;
            }

            while (this.byteBufferRead.hasRemaining()) {
                try {
                    //从Channel里读取数据,可能Slave没有发送过来进度
                    int readSize = this.socketChannel.read(this.byteBufferRead);
                    if (readSize > 0) {
                        readSizeZeroTimes = 0;
                        // 设置最后读取时间
                        this.lastReadTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                        //传递过来的数据大于8字节,有效的同步,8个字节代表着有一个有效的CommitLog Offset
                        if ((this.byteBufferRead.position() - this.processPostion) >= 8) {
                            // 读取Slave 请求来的CommitLog的最大位置
                            // position减去8的余数,这么做也是是为了防止流传输数据的粘包问题
                            int pos = this.byteBufferRead.position() - (this.byteBufferRead.position() % 8);
                            // 读取ByteBuffer中position内最大的8字节的倍数,比如 position = 571,则 571-(571%8)= 571-3 = 568
                            // 也就是读取 560-568位置的字节,因为Slave可能发送了多个进度过来,Master只读最末尾也就是最大的那个
                            long readOffset = this.byteBufferRead.getLong(pos - 8);
                            this.processPostion = pos;

                            // 设置Slave CommitLog的最大位置
                            HAConnection.this.slaveAckOffset = readOffset;

                            // 设置Slave 第一次请求的位置,初始化 slaveRequestOffset = Slave的请求位置
                            if (HAConnection.this.slaveRequestOffset < 0) {
                                HAConnection.this.slaveRequestOffset = readOffset;
                                log.info("slave[" + HAConnection.this.clientAddr + "] request offset " + readOffset);
                            }
                            // 通知目前Slave进度。主要用于Master节点为同步类型的。
                            HAConnection.this.haService.notifyTransferSome(HAConnection.this.slaveAckOffset);
                        }
                    } else if (readSize == 0) {
                        if (++readSizeZeroTimes >= 3) {
                            //连续读取3此没有数据,break,返回true,休眠
                            break;
                        }
                    } else {
                        log.error("read socket[" + HAConnection.this.clientAddr + "] < 0");
                        return false;
                    }
                } catch (IOException e) {
                    log.error("processReadEvent exception", e);
                    return false;
                }
            }

            return true;
        }
    }

    class WriteSocketService extends ServiceThread {
        private final Selector selector;
        private final SocketChannel socketChannel;

        private final int headerSize = 8 + 4;
        private final ByteBuffer byteBufferHeader = ByteBuffer.allocate(headerSize);
        private long nextTransferFromWhere = -1;
        private SelectMappedBufferResult selectMappedBufferResult;
        private boolean lastWriteOver = true;
        private long lastWriteTimestamp = System.currentTimeMillis();

        public WriteSocketService(final SocketChannel socketChannel) throws IOException {
            this.selector = RemotingUtil.openSelector();
            this.socketChannel = socketChannel;
            this.socketChannel.register(this.selector, SelectionKey.OP_WRITE);
            this.thread.setDaemon(true);
        }

        /**
         * 当Slave是第一次启动时，也就是其CommitLog里没有任何数据，发送的maxPhyOffset==0，那么Master在同步数据时会仅同步lastMappedFile，
         * 也就是从最后一个MappedFile的起始位置开始同步，忽略其前面的所有MappedFile。如果是中途宕机后的再次启动，那么会接着其CommitLog的进度继续同步，
         * 否则其CommitLog就会出现数据断层的情况。也正是基于这种机制，当Slave再次启动时，马上就上报进度，开始数据同步，
         * 因为其进度与Master的maxPhyOffset可能间隔了一个或多个MappedFile了，要抓紧时间跟上。
         *
         * Master每次同步CommitLog的数据大小最大为32KB，不包括Header，也就是8byte的offset，4byte的bodySize。
         *
         * 由以上可知，Master传输数据并不是按消息整体来传输的，也就是说很多时候会传递消息的一部分
         * Master会在一次数据写入SocketChannel后紧接着就进行下次写入，而不需要等待Slave返回ACK，也就是说不需要等待Slave返回确认收到
         * 当没有新消息时，WriteSocketService每休眠100ms运行一次
         *
         */
        @Override
        public void run() {
            HAConnection.log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    //由于没有 SelectionKey.OP_WRITE事件，每次都会阻塞1S
                    this.selector.select(1000);
                    //如果slaveRequestOffset没有没重置，因为不知道Slave需要同步的开始位置，会休眠，然后继续，
                    // 只有Slave把maxPhyOffset传给Master后才能往下
                    if (-1 == HAConnection.this.slaveRequestOffset) {
                        Thread.sleep(10);
                        continue;
                    }

                    if (-1 == this.nextTransferFromWhere) {
                        //计算下一次需要同步Slave的位置
                        if (0 == HAConnection.this.slaveRequestOffset) {
                            //如果Slave传的maxPhyOffset为0，那么Master只会同步最后一个MappedFile的数据到Slave
                            long masterOffset = HAConnection.this.haService.getDefaultMessageStore().getCommitLog().getMaxOffset();
                            masterOffset =
                                masterOffset
                                    - (masterOffset % HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig()
                                    .getMapedFileSizeCommitLog());

                            if (masterOffset < 0) {
                                masterOffset = 0;
                            }

                            this.nextTransferFromWhere = masterOffset;
                        } else {
                            this.nextTransferFromWhere = HAConnection.this.slaveRequestOffset;
                        }

                        log.info("master transfer data from " + this.nextTransferFromWhere + " to slave[" + HAConnection.this.clientAddr
                            + "], and slave request " + HAConnection.this.slaveRequestOffset);
                    }

                    if (this.lastWriteOver) {
                        //上次的数据已经传输完成
                        long interval =
                            HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now() - this.lastWriteTimestamp;
                        //如果距离上次同步(写数据给Slave)超过5S,也就是5S内没有新的消息,发送一个空包过去,就是没有实际数据的,刷新Slave的最后写入时间
                        if (interval > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig()
                            .getHaSendHeartbeatInterval()) {

                            // Build Header
                            this.byteBufferHeader.position(0);
                            this.byteBufferHeader.limit(headerSize);
                            this.byteBufferHeader.putLong(this.nextTransferFromWhere);
                            this.byteBufferHeader.putInt(0);
                            this.byteBufferHeader.flip();

                            this.lastWriteOver = this.transferData();
                            if (!this.lastWriteOver)
                                continue;
                        }
                    } else {
                        // 上次传输未完成，继续传输
                        this.lastWriteOver = this.transferData();
                        if (!this.lastWriteOver)
                            continue;
                    }

                    // 选择新的CommitLog内容进行传输，创建新的SelectMappedBufferResult
                    SelectMappedBufferResult selectResult =
                        HAConnection.this.haService.getDefaultMessageStore().getCommitLogData(this.nextTransferFromWhere);
                    if (selectResult != null) {
                        int size = selectResult.getSize();
                        if (size > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaTransferBatchSize()) {
                            size = HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaTransferBatchSize();
                        }

                        long thisOffset = this.nextTransferFromWhere;
                        this.nextTransferFromWhere += size;

                        selectResult.getByteBuffer().limit(size);
                        this.selectMappedBufferResult = selectResult;

                        // Build Header
                        this.byteBufferHeader.position(0);
                        // 头部大小12字节,前8 Offset ; 后4 是 totalSize
                        this.byteBufferHeader.limit(headerSize);
                        // 将同步的CommitLog的Offset存入
                        this.byteBufferHeader.putLong(thisOffset);
                        // 将同步的数据size存入
                        this.byteBufferHeader.putInt(size);
                        //转为写模式
                        this.byteBufferHeader.flip();

                        this.lastWriteOver = this.transferData();
                    } else {

                        HAConnection.this.haService.getWaitNotifyObject().allWaitForRunning(100);
                    }
                } catch (Exception e) {

                    HAConnection.log.error(this.getServiceName() + " service has exception.", e);
                    break;
                }
            }

            HAConnection.this.haService.getWaitNotifyObject().removeFromWaitingThreadTable();

            if (this.selectMappedBufferResult != null) {
                this.selectMappedBufferResult.release();
            }

            this.makeStop();

            readSocketService.makeStop();

            haService.removeConnection(HAConnection.this);

            SelectionKey sk = this.socketChannel.keyFor(this.selector);
            if (sk != null) {
                sk.cancel();
            }

            try {
                this.selector.close();
                this.socketChannel.close();
            } catch (IOException e) {
                HAConnection.log.error("", e);
            }

            HAConnection.log.info(this.getServiceName() + " service end");
        }

        private boolean transferData() throws Exception {
            int writeSizeZeroTimes = 0;
            // Write Header
            while (this.byteBufferHeader.hasRemaining()) {
                //如果请求头有数据，先传输请求头，同时更新最后写入时间
                int writeSize = this.socketChannel.write(this.byteBufferHeader);
                if (writeSize > 0) {
                    writeSizeZeroTimes = 0;
                    this.lastWriteTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                } else if (writeSize == 0) {
                    if (++writeSizeZeroTimes >= 3) {
                        break;
                    }
                } else {
                    throw new Exception("ha master write header error < 0");
                }
            }

            if (null == this.selectMappedBufferResult) {
                return !this.byteBufferHeader.hasRemaining();
            }

            writeSizeZeroTimes = 0;

            // Write Body
            if (!this.byteBufferHeader.hasRemaining()) {
                //请求头没有数据
                while (this.selectMappedBufferResult.getByteBuffer().hasRemaining()) {
                    //请求体有数据
                    int writeSize = this.socketChannel.write(this.selectMappedBufferResult.getByteBuffer());
                    if (writeSize > 0) {
                        writeSizeZeroTimes = 0;
                        this.lastWriteTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                    } else if (writeSize == 0) {
                        if (++writeSizeZeroTimes >= 3) {
                            break;
                        }
                    } else {
                        throw new Exception("ha master write body error < 0");
                    }
                }
            }

            boolean result = !this.byteBufferHeader.hasRemaining() && !this.selectMappedBufferResult.getByteBuffer().hasRemaining();

            if (!this.selectMappedBufferResult.getByteBuffer().hasRemaining()) {
                this.selectMappedBufferResult.release();
                this.selectMappedBufferResult = null;
            }
            //如果请求头和请求体的数据都传输完了，则返回true，否则，返回false
            return result;
        }

        @Override
        public String getServiceName() {
            return WriteSocketService.class.getSimpleName();
        }

        @Override
        public void shutdown() {
            super.shutdown();
        }
    }
}
