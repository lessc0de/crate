/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.protocols.postgres;

import org.apache.lucene.util.BytesRef;
import org.jboss.netty.buffer.ChannelBuffer;

import javax.annotation.Nonnull;

abstract class PGType {

    final int oid;
    final int typeLen;
    final int typeMod;
    final int formatCode;

    private PGType(int oid,  int typeLen, int typeMod, int formatCode) {
        this.oid = oid;
        this.typeLen = typeLen;
        this.typeMod = typeMod;
        this.formatCode = formatCode;
    }

    /**
     * write the value onto the buffer.
     * @return the number of bytes written.
     */
    abstract int writeValue(ChannelBuffer buffer, @Nonnull Object value);

    abstract Object readValue(ChannelBuffer buffer, int valueLength);

    static class StringType extends PGType {

        final static int OID = 1043;

        StringType() {
            super(OID, -1, -1, 0);
        }

        @Override
        int writeValue(ChannelBuffer buffer, @Nonnull Object value) {
            BytesRef bytesRef = (BytesRef) value;
            buffer.writeInt(bytesRef.length);
            buffer.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
            return 4 + bytesRef.length;
        }

        @Override
        Object readValue(ChannelBuffer buffer, int valueLength) {
            BytesRef bytesRef = new BytesRef(valueLength);
            bytesRef.length = valueLength;
            buffer.readBytes(bytesRef.bytes);
            return bytesRef;
        }
    }
}
