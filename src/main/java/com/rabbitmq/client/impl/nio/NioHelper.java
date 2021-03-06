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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

public class NioHelper {

    static int read(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
        int read = channel.read(buffer);
        if(read < 0) {
            throw new IOException("I/O thread: reached EOF");
        }
        return read;
    }

    static int retryRead(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
        int attempt = 0;
        int read = 0;
        while(attempt < 3) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                // ignore
            }
            read = read(channel, buffer);
            if(read > 0) {
                break;
            }
            attempt++;
        }
        return read;
    }

    static int readWithRetry(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
        int bytesRead = NioHelper.read(channel, buffer);
        if (bytesRead <= 0) {
            bytesRead = NioHelper.retryRead(channel, buffer);
        }
        return bytesRead;
    }
}
