// Copyright (c) 2007-Present Pivotal Software, Inc.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 1.1 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.client.impl.nio;

import com.rabbitmq.client.impl.AMQConnection;
import com.rabbitmq.client.impl.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class SocketChannelFrameHandlerState {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketChannelFrameHandlerState.class);

    /** Time to linger before closing the socket forcefully. */
    private static final int SOCKET_CLOSING_TIMEOUT = 1;

    private final SocketChannel channel;

    private final BlockingQueue<WriteRequest> writeQueue;

    private volatile AMQConnection connection;

    /** should be used only in the NIO read thread */
    private long lastActivity;

    private final SelectorHolder writeSelectorState;

    private final SelectorHolder readSelectorState;

    private final int writeEnqueuingTimeoutInMs;

    final boolean ssl;

    final SSLEngine sslEngine;

    /** outbound app data (to be crypted if TLS is on) */
    final ByteBuffer plainOut;

    /** inbound app data (deciphered if TLS is on) */
    final ByteBuffer plainIn;

    /** outbound net data (ciphered if TLS is on) */
    final ByteBuffer cipherOut;

    /** inbound data (ciphered if TLS is on) */
    final ByteBuffer cipherIn;

    final DataOutputStream outputStream;

    final DataInputStream inputStream;

    public SocketChannelFrameHandlerState(SocketChannel channel, NioLoopContext nioLoopsState, NioParams nioParams, SSLEngine sslEngine) {
        this.channel = channel;
        this.readSelectorState = nioLoopsState.readSelectorState;
        this.writeSelectorState = nioLoopsState.writeSelectorState;
        this.writeQueue = new ArrayBlockingQueue<WriteRequest>(nioParams.getWriteQueueCapacity(), true);
        this.writeEnqueuingTimeoutInMs = nioParams.getWriteEnqueuingTimeoutInMs();
        this.sslEngine = sslEngine;
        if(this.sslEngine == null) {
            this.ssl = false;
            this.plainOut = nioLoopsState.writeBuffer;
            this.cipherOut = null;
            this.plainIn = nioLoopsState.readBuffer;
            this.cipherIn = null;

            this.outputStream = new DataOutputStream(
                new ByteBufferOutputStream(channel, plainOut)
            );
            this.inputStream = new DataInputStream(
                new ByteBufferInputStream(channel, plainIn)
            );

        } else {
            this.ssl = true;
            this.plainOut = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());
            this.cipherOut = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
            this.plainIn = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());
            this.cipherIn = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());

            this.outputStream = new DataOutputStream(
                new SslEngineByteBufferOutputStream(sslEngine, plainOut, cipherOut, channel)
            );
            this.inputStream = new DataInputStream(
                new SslEngineByteBufferInputStream(sslEngine, plainIn, cipherIn, channel)
            );
        }

    }

    public SocketChannel getChannel() {
        return channel;
    }

    public Queue<WriteRequest> getWriteQueue() {
        return writeQueue;
    }

    public void sendHeader() throws IOException {
        sendWriteRequest(new HeaderWriteRequest());
    }

    public void write(Frame frame) throws IOException {
        sendWriteRequest(new FrameWriteRequest(frame));
    }

    private void sendWriteRequest(WriteRequest writeRequest) throws IOException {
        try {
            boolean offered = this.writeQueue.offer(writeRequest, writeEnqueuingTimeoutInMs, TimeUnit.MILLISECONDS);
            if(offered) {
                this.writeSelectorState.registerFrameHandlerState(this, SelectionKey.OP_WRITE);
                this.readSelectorState.selector.wakeup();
            } else {
                throw new IOException("Frame enqueuing failed");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Thread interrupted during enqueuing frame in write queue");
        }
    }

    public void startReading() {
        this.readSelectorState.registerFrameHandlerState(this, SelectionKey.OP_READ);
    }

    public AMQConnection getConnection() {
        return connection;
    }

    public void setConnection(AMQConnection connection) {
        this.connection = connection;
    }

    public void setLastActivity(long lastActivity) {
        this.lastActivity = lastActivity;
    }

    public long getLastActivity() {
        return lastActivity;
    }

    void prepareForWriteSequence() {
        if(ssl) {
            plainOut.clear();
            cipherOut.clear();
        }
    }

    void endWriteSequence() {
        if(!ssl) {
            plainOut.clear();
        }
    }

    void prepareForReadSequence() throws IOException {
        if(ssl) {
            cipherIn.clear();
            plainIn.clear();

            cipherIn.flip();
            plainIn.flip();
        } else {
            NioHelper.read(channel, plainIn);
            plainIn.flip();
        }
    }

    boolean continueReading() throws IOException {
        if(ssl) {
            if (!plainIn.hasRemaining() && !cipherIn.hasRemaining()) {
                // need to try to read something
                cipherIn.clear();
                int bytesRead = NioHelper.read(channel, cipherIn);
                if (bytesRead <= 0) {
                    return false;
                } else {
                    cipherIn.flip();
                    return true;
                }
            } else {
                return true;
            }
        } else {
            if (!plainIn.hasRemaining()) {
                plainIn.clear();
                NioHelper.read(channel, plainIn);
                plainIn.flip();
            }
            return plainIn.hasRemaining();
        }
    }

    void close() throws IOException {
        if(ssl) {
            SslEngineHelper.close(channel, sslEngine);
        }
        if(channel.isOpen()) {
            channel.socket().setSoLinger(true, SOCKET_CLOSING_TIMEOUT);
            channel.close();
        }
    }
}